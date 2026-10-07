package jadx.gui.privacy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import jadx.api.ResourceFile;
import jadx.api.security.IJadxSecurity;
import jadx.core.utils.android.AndroidManifestParser;
import jadx.core.utils.exceptions.JadxRuntimeException;

/**
 * Data from AndroidManifest.xml needed for the privacy analysis:
 * declared permissions and services/receivers with their protection permission and intent actions.
 */
public final class ManifestInfo {

	public enum ComponentType {
		SERVICE, RECEIVER
	}

	public static final class Component {
		private final ComponentType type;
		private final String clsName;
		private final @Nullable String permission;
		private final List<String> actions;

		public Component(ComponentType type, String clsName, @Nullable String permission, List<String> actions) {
			this.type = type;
			this.clsName = clsName;
			this.permission = permission;
			this.actions = actions;
		}

		public ComponentType getType() {
			return type;
		}

		/**
		 * Full class name as written in manifest (dots for inner classes are possible)
		 */
		public String getClsName() {
			return clsName;
		}

		public @Nullable String getPermission() {
			return permission;
		}

		public List<String> getActions() {
			return actions;
		}
	}

	private static final ManifestInfo EMPTY = new ManifestInfo("", Collections.emptySet(), Collections.emptyList());

	private final String pkg;
	private final Set<String> usesPermissions;
	private final List<Component> components;
	private final Set<String> componentPermissions;

	public ManifestInfo(String pkg, Set<String> usesPermissions, List<Component> components) {
		this.pkg = pkg;
		this.usesPermissions = usesPermissions;
		this.components = components;
		Set<String> perms = new LinkedHashSet<>();
		for (Component c : components) {
			if (c.getPermission() != null) {
				perms.add(c.getPermission());
			}
		}
		this.componentPermissions = perms;
	}

	public static ManifestInfo empty() {
		return EMPTY;
	}

	public static ManifestInfo fromResources(List<ResourceFile> resources, IJadxSecurity security) {
		ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(resources);
		if (manifestRes == null) {
			return EMPTY;
		}
		String xml = manifestRes.loadContent().getText().getCodeStr();
		return parse(xml, security);
	}

	public static ManifestInfo parse(String xml, IJadxSecurity security) {
		Document doc;
		try (InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
			doc = security.parseXml(in);
		} catch (Exception e) {
			throw new JadxRuntimeException("Failed to parse AndroidManifest.xml", e);
		}
		Element manifest = (Element) doc.getElementsByTagName("manifest").item(0);
		String pkg = manifest != null ? manifest.getAttribute("package") : "";

		Set<String> usesPerms = new LinkedHashSet<>();
		for (String tag : new String[] { "uses-permission", "uses-permission-sdk-23" }) {
			NodeList list = doc.getElementsByTagName(tag);
			for (int i = 0; i < list.getLength(); i++) {
				String name = ((Element) list.item(i)).getAttribute("android:name");
				if (!name.isEmpty()) {
					usesPerms.add(name);
				}
			}
		}
		List<Component> components = new ArrayList<>();
		addComponents(doc, "service", ComponentType.SERVICE, pkg, components);
		addComponents(doc, "receiver", ComponentType.RECEIVER, pkg, components);
		return new ManifestInfo(pkg, usesPerms, components);
	}

	private static void addComponents(Document doc, String tag, ComponentType type, String pkg, List<Component> components) {
		NodeList list = doc.getElementsByTagName(tag);
		for (int i = 0; i < list.getLength(); i++) {
			Element element = (Element) list.item(i);
			String name = element.getAttribute("android:name");
			if (name.isEmpty()) {
				continue;
			}
			String permission = element.getAttribute("android:permission");
			List<String> actions = new ArrayList<>();
			NodeList actionNodes = element.getElementsByTagName("action");
			for (int j = 0; j < actionNodes.getLength(); j++) {
				String action = ((Element) actionNodes.item(j)).getAttribute("android:name");
				if (!action.isEmpty()) {
					actions.add(action);
				}
			}
			components.add(new Component(type, resolveClassName(pkg, name),
					permission.isEmpty() ? null : permission, actions));
		}
	}

	static String resolveClassName(String pkg, String name) {
		if (name.startsWith(".")) {
			return pkg + name;
		}
		if (name.indexOf('.') == -1 && !pkg.isEmpty()) {
			return pkg + '.' + name;
		}
		return name;
	}

	public String getPackage() {
		return pkg;
	}

	public Set<String> getUsesPermissions() {
		return usesPermissions;
	}

	public List<Component> getComponents() {
		return components;
	}

	/**
	 * Permissions protecting manifest components ({@code android:permission} attribute)
	 */
	public Set<String> getComponentPermissions() {
		return componentPermissions;
	}
}
