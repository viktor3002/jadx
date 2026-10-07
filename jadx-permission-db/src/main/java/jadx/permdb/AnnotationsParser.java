package jadx.permdb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.jetbrains.annotations.Nullable;

import jadx.permdb.PermissionData.ApiKind;

/**
 * Parse {@code data/annotations.zip} of an SDK platform: external annotations (IntelliJ format)
 * used by Android Lint, including {@code @RequiresPermission} for framework methods and fields.
 *
 * <pre>
 * &lt;item name="android.location.LocationManager android.location.Location getLastKnownLocation(java.lang.String)"&gt;
 *   &lt;annotation name="androidx.annotation.RequiresPermission"&gt;
 *     &lt;val name="anyOf" val="{&amp;quot;android.permission.ACCESS_COARSE_LOCATION&amp;quot;, ...}" /&gt;
 * </pre>
 */
final class AnnotationsParser {
	static final String SOURCE = "sdk-annotations";

	private static final String REQUIRES_PERMISSION = "androidx.annotation.RequiresPermission";
	private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
	private static final byte[] LT = "&lt;".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] GT = "&gt;".getBytes(StandardCharsets.US_ASCII);

	/**
	 * Parsed {@code @RequiresPermission} of one item
	 */
	static final class Item {
		final ApiKind kind;
		final String cls;
		final String member;
		final @Nullable String params;
		final List<String> permissions;
		/** 'all' or 'any' */
		final String mode;
		final boolean conditional;
		final @Nullable String access;

		Item(ApiKind kind, String cls, String member, @Nullable String params, List<String> permissions, String mode,
				boolean conditional, @Nullable String access) {
			this.kind = kind;
			this.cls = cls;
			this.member = member;
			this.params = params;
			this.permissions = permissions;
			this.mode = mode;
			this.conditional = conditional;
			this.access = access;
		}
	}

	private AnnotationsParser() {
	}

	static void apply(PermissionData data, byte[] annotationsZip, int level) throws IOException {
		for (Item item : parseZip(annotationsZip)) {
			PermissionData.Api api = data.getOrAddApi(item.kind, item.cls, item.member, item.params);
			for (String perm : item.permissions) {
				data.addRequirement(api, perm, item.mode, item.conditional, item.access, SOURCE, level);
			}
		}
	}

	static List<Item> parseZip(byte[] annotationsZip) throws IOException {
		List<Item> items = new ArrayList<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(annotationsZip))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().endsWith(".xml")) {
					items.addAll(parseXml(zip.readAllBytes()));
				}
			}
		}
		return items;
	}

	static List<Item> parseXml(byte[] xml) throws IOException {
		List<Item> items = new ArrayList<>();
		try {
			XMLInputFactory factory = XMLInputFactory.newFactory();
			factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
			factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
			XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(escapeAttributeBrackets(xml)));
			String itemName = null;
			String annotation = null;
			List<String> perms = new ArrayList<>();
			String mode = "all";
			boolean conditional = false;
			while (reader.hasNext()) {
				int event = reader.next();
				if (event == XMLStreamConstants.START_ELEMENT) {
					switch (reader.getLocalName()) {
						case "item":
							itemName = reader.getAttributeValue(null, "name");
							break;
						case "annotation":
							annotation = reader.getAttributeValue(null, "name");
							perms = new ArrayList<>();
							mode = "all";
							conditional = false;
							break;
						case "val":
							if (annotation != null && annotation.startsWith(REQUIRES_PERMISSION)) {
								String name = reader.getAttributeValue(null, "name");
								String val = reader.getAttributeValue(null, "val");
								if ("conditional".equals(name)) {
									conditional = "true".equals(val);
								} else if (val != null) {
									if ("anyOf".equals(name) || val.contains("anyOf")) {
										mode = "any";
									}
									Matcher m = QUOTED.matcher(val);
									while (m.find()) {
										perms.add(m.group(1));
									}
								}
							}
							break;
						default:
							break;
					}
				} else if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("annotation")) {
					if (itemName != null && annotation != null && annotation.startsWith(REQUIRES_PERMISSION) && !perms.isEmpty()) {
						Item item = parseItem(itemName, perms, mode, conditional, accessOf(annotation));
						if (item != null) {
							items.add(item);
						}
					}
					annotation = null;
				}
			}
			reader.close();
		} catch (XMLStreamException e) {
			throw new IOException("Failed to parse annotations xml", e);
		}
		return items;
	}

	private static @Nullable String accessOf(String annotation) {
		if (annotation.endsWith(".Read")) {
			return "read";
		}
		if (annotation.endsWith(".Write")) {
			return "write";
		}
		return null;
	}

	/**
	 * @return null for parameter annotations (not supported)
	 */
	static @Nullable Item parseItem(String itemName, List<String> perms, String mode, boolean conditional, @Nullable String access) {
		int space = itemName.indexOf(' ');
		if (space == -1) {
			return null; // class annotation
		}
		String cls = JavaNames.toBinaryName(itemName.substring(0, space));
		String rest = itemName.substring(space + 1).trim();
		int open = rest.indexOf('(');
		if (open == -1) {
			return new Item(ApiKind.FIELD, cls, rest, null, perms, mode, conditional, access);
		}
		int close = rest.lastIndexOf(')');
		if (close == -1 || !rest.substring(close + 1).isBlank()) {
			return null; // parameter annotation: '... method(int) 0'
		}
		String beforeParen = rest.substring(0, open).trim();
		int nameStart = beforeParen.lastIndexOf(' ');
		String name = beforeParen.substring(nameStart + 1);
		if (nameStart == -1) {
			// constructors have no return type
			name = "<init>";
		}
		String params = JavaNames.eraseParams(rest.substring(open + 1, close));
		return new Item(ApiKind.METHOD, cls, name, params, perms, mode, conditional, access);
	}

	/**
	 * Some SDK versions contain unescaped generics in attribute values
	 * ({@code name="... foo(java.util.List<java.lang.String>)"}), which is not valid XML.
	 */
	static byte[] escapeAttributeBrackets(byte[] xml) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(xml.length + 1024);
		boolean inTag = false;
		byte quote = 0;
		for (byte b : xml) {
			if (quote != 0) {
				if (b == quote) {
					quote = 0;
				} else if (b == '<') {
					out.writeBytes(LT);
					continue;
				} else if (b == '>') {
					out.writeBytes(GT);
					continue;
				}
			} else if (inTag) {
				if (b == '"' || b == '\'') {
					quote = b;
				} else if (b == '>') {
					inTag = false;
				}
			} else if (b == '<') {
				inTag = true;
			}
			out.write(b);
		}
		return out.toByteArray();
	}
}
