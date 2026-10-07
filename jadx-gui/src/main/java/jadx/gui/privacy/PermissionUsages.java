package jadx.gui.privacy;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jetbrains.annotations.Nullable;

import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.gui.privacy.ManifestInfo.Component;
import jadx.gui.privacy.PrivacyReport.RowKind;
import jadx.gui.privacy.PrivacyRules.Api;
import jadx.gui.privacy.PrivacyRules.Capability;

/**
 * Every place in the app code which uses a permission: the APIs needing it, the methods calling
 * them and the call paths from services/receivers to these methods.
 */
public final class PermissionUsages {

	/**
	 * Service or receiver (from the manifest or registered at runtime)
	 */
	public static final class EntryComponent {
		private final RowKind kind;
		private final ClassNode cls;

		EntryComponent(RowKind kind, ClassNode cls) {
			this.kind = kind;
			this.cls = cls;
		}

		public RowKind getKind() {
			return kind;
		}

		public ClassNode getCls() {
			return cls;
		}
	}

	public static final class EntryPath {
		private final EntryComponent component;
		/** entry method first, call site last */
		private final List<MethodNode> path;

		EntryPath(EntryComponent component, List<MethodNode> path) {
			this.component = component;
			this.path = path;
		}

		public EntryComponent getComponent() {
			return component;
		}

		public List<MethodNode> getPath() {
			return path;
		}
	}

	public static final class CallSite {
		private final MethodNode method;
		private final List<EntryPath> entries;

		CallSite(MethodNode method, List<EntryPath> entries) {
			this.method = method;
			this.entries = entries;
		}

		/**
		 * Method which calls the API
		 */
		public MethodNode getMethod() {
			return method;
		}

		/**
		 * Shortest path from each service/receiver which reaches the call site (empty if none
		 * does, e.g. only reachable from an activity)
		 */
		public List<EntryPath> getEntries() {
			return entries;
		}
	}

	public static final class ApiUsage {
		private final Api api;
		private final List<CallSite> callSites;
		private final boolean moreCallSites;

		ApiUsage(Api api, List<CallSite> callSites, boolean moreCallSites) {
			this.api = api;
			this.callSites = callSites;
			this.moreCallSites = moreCallSites;
		}

		public Api getApi() {
			return api;
		}

		public List<CallSite> getCallSites() {
			return callSites;
		}

		/**
		 * More call sites exist than listed
		 */
		public boolean hasMoreCallSites() {
			return moreCallSites;
		}
	}

	private static final int MAX_ENTRIES_PER_SITE = 10;
	private static final int MAX_PATHS_SEARCH = 200;

	private final String permission;
	private final boolean declared;
	private final List<String> manifestUsages;
	private final List<ApiUsage> apis;

	private PermissionUsages(String permission, boolean declared, List<String> manifestUsages, List<ApiUsage> apis) {
		this.permission = permission;
		this.declared = declared;
		this.manifestUsages = manifestUsages;
		this.apis = apis;
	}

	public static PermissionUsages compute(PrivacyCallGraph graph, ManifestInfo manifest, String permission, int maxCallSites) {
		PrivacyRules rules = graph.getRules();
		Map<MethodNode, List<EntryComponent>> entryMethods = collectEntryMethods(graph, manifest);
		List<ApiUsage> usages = new ArrayList<>();
		BitSet used = graph.getUsedApis();
		for (int i = used.nextSetBit(0); i >= 0; i = used.nextSetBit(i + 1)) {
			Api api = rules.getApis().get(i);
			if (api.getCapability().getFlag() != null || !api.getPermissions().contains(permission)) {
				continue;
			}
			List<MethodNode> users = graph.getDirectUsers(i);
			List<CallSite> sites = new ArrayList<>();
			for (MethodNode user : users) {
				if (sites.size() >= maxCallSites) {
					break;
				}
				sites.add(new CallSite(user, findEntries(graph, user, entryMethods)));
			}
			usages.add(new ApiUsage(api, sites, users.size() > sites.size()));
		}
		usages.sort((a, b) -> a.getApi().getLabel().compareTo(b.getApi().getLabel()));
		return new PermissionUsages(permission, manifest.getUsesPermissions().contains(permission),
				collectManifestUsages(rules, manifest, permission), usages);
	}

	/**
	 * Number of used APIs per permission
	 */
	public static Map<String, Integer> countApisByPermission(PrivacyCallGraph graph) {
		Map<String, Integer> counts = new TreeMap<>();
		List<Api> apis = graph.getRules().getApis();
		BitSet used = graph.getUsedApis();
		for (int i = used.nextSetBit(0); i >= 0; i = used.nextSetBit(i + 1)) {
			Api api = apis.get(i);
			if (api.getCapability().getFlag() == null) {
				for (String perm : api.getPermissions()) {
					counts.merge(perm, 1, Integer::sum);
				}
			}
		}
		return counts;
	}

	private static List<EntryPath> findEntries(PrivacyCallGraph graph, MethodNode site,
			Map<MethodNode, List<EntryComponent>> entryMethods) {
		List<List<MethodNode>> paths = graph.findPathsTo(site, entryMethods.keySet(), MAX_PATHS_SEARCH);
		Map<ClassNode, EntryPath> byComponent = new LinkedHashMap<>();
		for (List<MethodNode> path : paths) {
			for (EntryComponent comp : entryMethods.get(path.get(0))) {
				byComponent.putIfAbsent(comp.getCls(), new EntryPath(comp, path));
			}
			if (byComponent.size() >= MAX_ENTRIES_PER_SITE) {
				break;
			}
		}
		return new ArrayList<>(byComponent.values());
	}

	private static Map<MethodNode, List<EntryComponent>> collectEntryMethods(PrivacyCallGraph graph, ManifestInfo manifest) {
		RootNode root = graph.getRoot();
		Map<MethodNode, List<EntryComponent>> map = new IdentityHashMap<>();
		Set<ClassNode> manifestClasses = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Component comp : manifest.getComponents()) {
			ClassNode cls = PrivacyAnalyzer.resolveClass(root, comp.getClsName());
			if (cls != null) {
				manifestClasses.add(cls);
				RowKind kind = comp.getType() == ManifestInfo.ComponentType.SERVICE ? RowKind.SERVICE : RowKind.RECEIVER;
				addEntry(map, graph, new EntryComponent(kind, cls));
			}
		}
		if (graph.isRegisterReceiverUsed()) {
			for (ClassNode cls : root.getClasses()) {
				if (!manifestClasses.contains(cls) && graph.isInstantiated(cls) && PrivacyCallGraph.isBroadcastReceiver(cls)) {
					addEntry(map, graph, new EntryComponent(RowKind.DYNAMIC_RECEIVER, cls));
				}
			}
		}
		return map;
	}

	private static void addEntry(Map<MethodNode, List<EntryComponent>> map, PrivacyCallGraph graph, EntryComponent comp) {
		for (MethodNode mth : PrivacyAnalyzer.componentRoots(graph, comp.getCls())) {
			map.computeIfAbsent(mth, m -> new ArrayList<>(1)).add(comp);
		}
	}

	/**
	 * Components protected by the permission and intent filters for broadcasts which need it
	 */
	private static List<String> collectManifestUsages(PrivacyRules rules, ManifestInfo manifest, String permission) {
		List<String> list = new ArrayList<>();
		for (Component comp : manifest.getComponents()) {
			String type = comp.getType() == ManifestInfo.ComponentType.SERVICE ? "<service>" : "<receiver>";
			if (permission.equals(comp.getPermission())) {
				list.add(type + ' ' + comp.getClsName() + "  android:permission=\"" + permission + '"');
			}
			for (String action : comp.getActions()) {
				Capability cap = rules.getIntentActions().get(action);
				String broadcastPerm = getBroadcastPermission(rules, action);
				if (permission.equals(broadcastPerm)
						|| (broadcastPerm == null && cap != null && cap.getPermissions().contains(permission))) {
					list.add(type + ' ' + comp.getClsName() + "  <action android:name=\"" + action + "\"/>");
				}
			}
		}
		return list;
	}

	private static @Nullable String getBroadcastPermission(PrivacyRules rules, String action) {
		PermissionDb db = rules.getPermissionDb();
		return db == null ? null : db.getBroadcastPermissions().get(action);
	}

	public String getPermission() {
		return permission;
	}

	/**
	 * Permission is declared with {@code <uses-permission>}
	 */
	public boolean isDeclared() {
		return declared;
	}

	public List<String> getManifestUsages() {
		return manifestUsages;
	}

	public List<ApiUsage> getApis() {
		return apis;
	}
}
