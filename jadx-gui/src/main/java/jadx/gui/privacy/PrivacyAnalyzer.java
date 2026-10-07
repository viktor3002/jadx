package jadx.gui.privacy;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.gui.privacy.ManifestInfo.Component;
import jadx.gui.privacy.PrivacyReport.Row;
import jadx.gui.privacy.PrivacyReport.RowKind;
import jadx.gui.privacy.PrivacyRules.Api;
import jadx.gui.privacy.PrivacyRules.Capability;

/**
 * Builds {@link PrivacyReport} rows for manifest components, dynamically registered receivers and
 * classes.
 */
public final class PrivacyAnalyzer {

	private PrivacyAnalyzer() {
	}

	public static PrivacyReport analyze(RootNode root, PrivacyRules rules, ManifestInfo manifest,
			String inputsHash, BooleanSupplier canceled) {
		PrivacyCallGraph graph = PrivacyCallGraph.build(root, rules, manifest, canceled);
		return buildReport(graph, manifest, inputsHash);
	}

	public static PrivacyReport buildReport(PrivacyCallGraph graph, ManifestInfo manifest, String inputsHash) {
		RootNode root = graph.getRoot();
		PrivacyRules rules = graph.getRules();

		List<Row> components = new ArrayList<>();
		Set<ClassNode> manifestClasses = new HashSet<>();
		for (Component comp : manifest.getComponents()) {
			ClassNode cls = resolveClass(root, comp.getClsName());
			Set<String> hits = new LinkedHashSet<>();
			if (cls != null) {
				manifestClasses.add(cls);
				hits.addAll(toKeys(rules, collectHits(graph, componentRoots(graph, cls), false)));
			}
			addManifestHits(rules, manifest, comp, hits);
			RowKind kind = comp.getType() == ManifestInfo.ComponentType.SERVICE ? RowKind.SERVICE : RowKind.RECEIVER;
			String clsName = cls != null ? cls.getRawName() : comp.getClsName();
			components.add(new Row(kind, clsName, cls == null, new ArrayList<>(hits), new ArrayList<>()));
		}
		if (graph.isRegisterReceiverUsed()) {
			for (ClassNode cls : root.getClasses()) {
				if (!manifestClasses.contains(cls)
						&& graph.isInstantiated(cls)
						&& PrivacyCallGraph.isBroadcastReceiver(cls)) {
					List<String> hits = toKeys(rules, collectHits(graph, componentRoots(graph, cls), false));
					components.add(new Row(RowKind.DYNAMIC_RECEIVER, cls.getRawName(), false, hits, new ArrayList<>()));
				}
			}
		}

		List<Row> classes = new ArrayList<>();
		for (ClassNode cls : root.getClassesWithoutInner()) {
			Set<MethodNode> roots = classRoots(cls);
			List<String> hits = toKeys(rules, collectHits(graph, roots, false));
			if (!hits.isEmpty()) {
				BitSet directHits = collectHits(graph, roots, true);
				classes.add(new Row(RowKind.CLASS, cls.getRawName(), false, hits, toKeys(rules, directHits)));
			}
		}
		return new PrivacyReport(inputsHash, rules.getFingerprint(), components, classes);
	}

	/**
	 * Add at most one manifest entry per capability (protection permission and intent action often
	 * describe the same)
	 */
	private static void addManifestHits(PrivacyRules rules, ManifestInfo manifest, Component comp, Set<String> hits) {
		Set<Capability> added = new HashSet<>();
		String permission = comp.getPermission();
		if (permission != null) {
			Capability cap = rules.getComponentPermissions().get(permission);
			if (cap != null && cap.isPrivacy() && PrivacyRules.isActive(cap, manifest) && added.add(cap)) {
				hits.add(PrivacyRules.manifestPermissionKey(permission));
			}
		}
		for (String action : comp.getActions()) {
			Capability cap = rules.getIntentActions().get(action);
			if (cap != null && cap.isPrivacy() && PrivacyRules.isActive(cap, manifest) && added.add(cap)) {
				hits.add(PrivacyRules.manifestActionKey(action));
			}
		}
	}

	private static BitSet collectHits(PrivacyCallGraph graph, Set<MethodNode> roots, boolean directOnly) {
		BitSet bits = new BitSet();
		for (MethodNode mth : roots) {
			bits.or(directOnly ? graph.getDirectHits(mth) : graph.getReachHits(mth));
		}
		return bits;
	}

	private static List<String> toKeys(PrivacyRules rules, BitSet bits) {
		List<String> keys = new ArrayList<>(bits.cardinality());
		for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
			Api api = rules.getApis().get(i);
			if (api.getCapability().isPrivacy()) {
				keys.add(api.getKey());
			}
		}
		return keys;
	}

	/**
	 * Entry points of a component: all methods of the class, its app superclasses and inner classes.
	 */
	public static Set<MethodNode> componentRoots(PrivacyCallGraph graph, ClassNode cls) {
		Set<MethodNode> roots = classRoots(cls);
		ClassNode superCls = graph.getAppSuperClass(cls);
		Set<ClassNode> visited = new HashSet<>();
		while (superCls != null && visited.add(superCls)) {
			roots.addAll(superCls.getMethods());
			superCls = graph.getAppSuperClass(superCls);
		}
		return roots;
	}

	/**
	 * All methods of the class and its inner classes
	 */
	public static Set<MethodNode> classRoots(ClassNode cls) {
		Set<ClassNode> classes = new LinkedHashSet<>();
		classes.add(cls);
		cls.getInnerClassesRecursive(classes);
		Set<MethodNode> roots = new LinkedHashSet<>();
		for (ClassNode c : classes) {
			roots.addAll(c.getMethods());
		}
		return roots;
	}

	public static @Nullable ClassNode resolveClass(RootNode root, String name) {
		ClassNode cls = root.resolveRawClass(name);
		if (cls == null) {
			cls = root.resolveClass(name);
		}
		return cls;
	}
}
