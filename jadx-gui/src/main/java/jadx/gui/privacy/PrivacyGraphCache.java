package jadx.gui.privacy;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

import jadx.core.dex.nodes.RootNode;
import jadx.gui.JadxWrapper;

/**
 * Call graph of the currently loaded app, shared by the overview and the permission usages
 * windows (building it takes a few seconds for large apps)
 */
public final class PrivacyGraphCache {
	private static final Map<RootNode, PrivacyCallGraph> CACHE = new WeakHashMap<>();

	private PrivacyGraphCache() {
	}

	/**
	 * @return cached graph or null if not built yet
	 */
	public static @Nullable PrivacyCallGraph getIfBuilt(JadxWrapper wrapper, PrivacyRules rules) {
		synchronized (CACHE) {
			PrivacyCallGraph graph = CACHE.get(wrapper.getRootNode());
			return graph != null && graph.getRules() == rules ? graph : null;
		}
	}

	/**
	 * Get cached graph or build it (slow, call from background thread)
	 */
	public static PrivacyCallGraph get(JadxWrapper wrapper, PrivacyRules rules, BooleanSupplier canceled) {
		PrivacyCallGraph cached = getIfBuilt(wrapper, rules);
		if (cached != null) {
			return cached;
		}
		return build(wrapper, rules, canceled);
	}

	/**
	 * Build a new graph and replace the cached one
	 */
	public static PrivacyCallGraph build(JadxWrapper wrapper, PrivacyRules rules, BooleanSupplier canceled) {
		RootNode root = wrapper.getRootNode();
		ManifestInfo manifest = PrivacyManifestCache.get(wrapper);
		PrivacyCallGraph graph = PrivacyCallGraph.build(root, rules, manifest, canceled);
		synchronized (CACHE) {
			CACHE.put(root, graph);
		}
		return graph;
	}
}
