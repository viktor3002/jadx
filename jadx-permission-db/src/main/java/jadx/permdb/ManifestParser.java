package jadx.permdb;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import jadx.permdb.PermissionData.PermissionInfo;
import jadx.permdb.PermissionData.ProviderPermission;

/**
 * Parse AOSP AndroidManifest.xml files: permission definitions and protected broadcasts of the
 * framework ({@code frameworks/base/core/res}), read/write permissions of system content
 * providers.
 */
final class ManifestParser {

	private ManifestParser() {
	}

	/**
	 * Framework manifest: {@code <permission>} and {@code <protected-broadcast>}
	 */
	static void parseFramework(PermissionData data, byte[] xml, String source) throws IOException {
		Document doc = parse(xml);
		NodeList perms = doc.getElementsByTagName("permission");
		for (int i = 0; i < perms.getLength(); i++) {
			Element e = (Element) perms.item(i);
			String name = attr(e, "name");
			if (name != null) {
				data.addPermission(new PermissionInfo(name, attr(e, "protectionLevel"), attr(e, "permissionGroup"),
						attr(e, "permissionFlags"), source));
			}
		}
		NodeList broadcasts = doc.getElementsByTagName("protected-broadcast");
		for (int i = 0; i < broadcasts.getLength(); i++) {
			String name = attr((Element) broadcasts.item(i), "name");
			if (name != null) {
				data.getOrAddBroadcast(name, source).setProtectedBroadcast(true);
			}
		}
	}

	/**
	 * Provider manifest: {@code <provider>} with read/write permissions and
	 * {@code <path-permission>}. Not exported providers are skipped.
	 */
	static void parseProviders(PermissionData data, byte[] xml, String source) throws IOException {
		Document doc = parse(xml);
		String pkg = doc.getDocumentElement().getAttribute("package");
		NodeList providers = doc.getElementsByTagName("provider");
		for (int i = 0; i < providers.getLength(); i++) {
			Element provider = (Element) providers.item(i);
			String authorities = attr(provider, "authorities");
			if (authorities == null || "false".equals(attr(provider, "exported"))) {
				continue;
			}
			for (String authority : authorities.split(";")) {
				String auth = authority.trim();
				if (auth.isEmpty()) {
					continue;
				}
				addAccess(data, auth, "", "", provider, pkg, source);
				NodeList children = provider.getChildNodes();
				for (int j = 0; j < children.getLength(); j++) {
					Node child = children.item(j);
					if (child instanceof Element && ((Element) child).getTagName().equals("path-permission")) {
						Element pathPerm = (Element) child;
						for (String pathType : new String[] { "path", "pathPrefix", "pathPattern" }) {
							String path = attr(pathPerm, pathType);
							if (path != null) {
								String type = pathType.equals("path") ? "path" : pathType.equals("pathPrefix") ? "prefix" : "pattern";
								addAccess(data, auth, path, type, pathPerm, pkg, source);
							}
						}
					}
				}
			}
		}
	}

	private static void addAccess(PermissionData data, String authority, String path, String pathType, Element e,
			String pkg, String source) {
		String perm = attr(e, "permission");
		String read = attr(e, "readPermission");
		String write = attr(e, "writePermission");
		if (read == null) {
			read = perm;
		}
		if (write == null) {
			write = perm;
		}
		String p = pkg == null || pkg.isEmpty() ? null : pkg;
		if (read != null) {
			data.addProvider(new ProviderPermission(authority, path, pathType, "read", read, p, source));
		}
		if (write != null) {
			data.addProvider(new ProviderPermission(authority, path, pathType, "write", write, p, source));
		}
	}

	private static @Nullable String attr(Element e, String name) {
		String value = e.getAttribute("android:" + name);
		return value.isEmpty() ? null : value;
	}

	private static Document parse(byte[] xml) throws IOException {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setNamespaceAware(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			return builder.parse(new ByteArrayInputStream(xml));
		} catch (ParserConfigurationException | SAXException e) {
			throw new IOException("Failed to parse manifest", e);
		}
	}
}
