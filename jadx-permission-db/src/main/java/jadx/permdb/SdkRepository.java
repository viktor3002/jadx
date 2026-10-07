package jadx.permdb;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Android SDK repository manifest ({@code repository2-3.xml}), lists all SDK platform packages
 * with download URL and checksum. New Android versions appear here automatically.
 */
final class SdkRepository {
	static final String BASE_URL = "https://dl.google.com/android/repository/";
	static final String MANIFEST_URL = BASE_URL + "repository2-3.xml";

	private static final String STABLE_CHANNEL = "channel-0";

	static final class Platform {
		private final String path;
		/** like '36' or '36.1' */
		private final String version;
		private final int apiLevel;
		private final int minor;
		private final String url;
		private final String sha1;

		Platform(String path, String version, int apiLevel, int minor, String url, String sha1) {
			this.path = path;
			this.version = version;
			this.apiLevel = apiLevel;
			this.minor = minor;
			this.url = url;
			this.sha1 = sha1;
		}

		String getPath() {
			return path;
		}

		String getVersion() {
			return version;
		}

		int getApiLevel() {
			return apiLevel;
		}

		int getMinor() {
			return minor;
		}

		String getUrl() {
			return url;
		}

		String getSha1() {
			return sha1;
		}

		@Override
		public String toString() {
			return path + " (" + url + ')';
		}
	}

	private SdkRepository() {
	}

	/**
	 * Stable platforms (no previews, no SDK extension variants), sorted by version
	 */
	static List<Platform> parseStablePlatforms(byte[] xml) throws IOException {
		Document doc = parse(xml);
		List<Platform> list = new ArrayList<>();
		NodeList packages = doc.getElementsByTagName("remotePackage");
		for (int i = 0; i < packages.getLength(); i++) {
			Element pkg = (Element) packages.item(i);
			String path = pkg.getAttribute("path");
			if (!path.startsWith("platforms;android-") || "true".equals(pkg.getAttribute("obsolete"))) {
				continue;
			}
			Element channel = firstChild(pkg, "channelRef");
			if (channel == null || !STABLE_CHANNEL.equals(channel.getAttribute("ref"))) {
				continue;
			}
			String baseExt = text(pkg, "base-extension");
			if ("false".equals(baseExt)) {
				continue; // extension variant (android-36-ext19), same API as base platform
			}
			String codename = text(pkg, "codename");
			if (codename != null && !codename.isEmpty()) {
				continue; // preview
			}
			String version = text(pkg, "api-level");
			if (version == null || !version.matches("\\d+(\\.\\d+)?")) {
				continue;
			}
			Element complete = firstChild(pkg, "complete");
			if (complete == null) {
				continue;
			}
			String url = text(complete, "url");
			String sha1 = text(complete, "checksum");
			if (url == null || sha1 == null) {
				continue;
			}
			String[] parts = version.split("\\.");
			int api = Integer.parseInt(parts[0]);
			int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
			list.add(new Platform(path, version, api, minor, url.startsWith("http") ? url : BASE_URL + url, sha1));
		}
		list.sort(Comparator.comparingInt(Platform::getApiLevel).thenComparingInt(Platform::getMinor));
		return list;
	}

	private static @Nullable Element firstChild(Element parent, String tag) {
		NodeList list = parent.getElementsByTagName(tag);
		return list.getLength() == 0 ? null : (Element) list.item(0);
	}

	private static @Nullable String text(Element parent, String tag) {
		Element e = firstChild(parent, tag);
		return e == null ? null : e.getTextContent().trim();
	}

	private static Document parse(byte[] xml) throws IOException {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			// ignore namespace prefixes ('sdk:', 'common:')
			factory.setNamespaceAware(true);
			Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
			doc.getDocumentElement().normalize();
			return doc;
		} catch (ParserConfigurationException | SAXException e) {
			throw new IOException("Failed to parse SDK repository manifest", e);
		}
	}
}
