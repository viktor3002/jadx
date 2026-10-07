package jadx.gui.privacy;

import java.util.Map;
import java.util.WeakHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.core.dex.nodes.RootNode;
import jadx.gui.JadxWrapper;

/**
 * Parsed manifest of the currently loaded app, reused by the overview window and the code
 * highlighting
 */
public final class PrivacyManifestCache {
	private static final Logger LOG = LoggerFactory.getLogger(PrivacyManifestCache.class);

	private static final Map<RootNode, ManifestInfo> CACHE = new WeakHashMap<>();

	private PrivacyManifestCache() {
	}

	public static ManifestInfo get(JadxWrapper wrapper) {
		RootNode root = wrapper.getRootNode();
		synchronized (CACHE) {
			ManifestInfo cached = CACHE.get(root);
			if (cached != null) {
				return cached;
			}
		}
		ManifestInfo manifest;
		try {
			manifest = ManifestInfo.fromResources(wrapper.getResources(), wrapper.getArgs().getSecurity());
		} catch (Exception e) {
			LOG.warn("Failed to load AndroidManifest.xml for privacy analysis", e);
			manifest = ManifestInfo.empty();
		}
		synchronized (CACHE) {
			CACHE.put(root, manifest);
		}
		return manifest;
	}
}
