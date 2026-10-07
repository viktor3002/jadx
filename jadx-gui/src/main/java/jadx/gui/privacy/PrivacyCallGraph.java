package jadx.gui.privacy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.plugins.input.insns.Opcode;
import jadx.core.clsp.ClspGraph;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.MethodOverrideAttr;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.IMethodDetails;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.utils.exceptions.JadxRuntimeException;
import jadx.gui.privacy.PrivacyApiMatcher.Hit;

/**
 * App wide call graph with privacy relevant API usage per method.
 * <br>
 * Built directly from raw instructions (no decompilation needed). Edges:
 * <ul>
 * <li>direct calls to app methods</li>
 * <li>virtual/interface calls: also to overriding methods in subclasses</li>
 * <li>lambdas and method references (invoke-custom)</li>
 * <li>object creation ({@code new C}): to all methods of {@code C} which override a framework
 * method, i.e. callbacks like {@code Runnable.run}, {@code onClick}, {@code onReceive}</li>
 * </ul>
 */
public final class PrivacyCallGraph {
	private static final Logger LOG = LoggerFactory.getLogger(PrivacyCallGraph.class);

	private static final String BROADCAST_RECEIVER = "android.content.BroadcastReceiver";

	private final RootNode root;
	private final PrivacyRules rules;
	private final List<MethodNode> methods = new ArrayList<>();
	private final Map<MethodNode, Integer> ids = new IdentityHashMap<>();
	private final Set<ClassNode> instantiated = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Map<ClassNode, List<MethodNode>> callbacksCache = new IdentityHashMap<>();
	private final PrivacyInsnScanner scanner;

	private int[][] succ;
	private BitSet[] direct;
	private BitSet[] reach;
	/** callers of each method, built on first use */
	private int[][] pred;
	private boolean registerReceiverUsed;

	private PrivacyCallGraph(RootNode root, PrivacyRules rules, ManifestInfo manifest) {
		this.root = root;
		this.rules = rules;
		// APIs of capabilities not declared in manifest are treated as dead code
		this.scanner = new PrivacyInsnScanner(root, new PrivacyApiMatcher(rules, manifest, root.getClsp()));
	}

	public static PrivacyCallGraph build(RootNode root, PrivacyRules rules, ManifestInfo manifest, BooleanSupplier canceled) {
		PrivacyCallGraph graph = new PrivacyCallGraph(root, rules, manifest);
		graph.collect(canceled);
		graph.computeReach();
		return graph;
	}

	private void collect(BooleanSupplier canceled) {
		for (ClassNode cls : root.getClasses()) {
			for (MethodNode mth : cls.getMethods()) {
				ids.put(mth, methods.size());
				methods.add(mth);
			}
		}
		int count = methods.size();
		succ = new int[count][];
		direct = new BitSet[count];
		Set<Integer> edges = new LinkedHashSet<>();
		for (int i = 0; i < count; i++) {
			if ((i & 0xFF) == 0 && canceled.getAsBoolean()) {
				throw new JadxRuntimeException("Privacy analysis canceled");
			}
			MethodNode mth = methods.get(i);
			edges.clear();
			BitSet hits = new BitSet();
			try {
				scanMethod(mth, edges, hits);
			} catch (Exception e) {
				LOG.debug("Privacy scan failed for method: {}", mth, e);
			}
			succ[i] = edges.stream().mapToInt(Integer::intValue).toArray();
			direct[i] = hits.isEmpty() ? null : hits;
		}
	}

	private void scanMethod(MethodNode mth, Set<Integer> edges, BitSet hits) {
		scanner.scan(mth, new PrivacyInsnScanner.Visitor() {
			@Override
			public void api(Hit hit, String token) {
				hits.set(hit.getApi().getIndex());
			}

			@Override
			public void invoke(MethodInfo mthInfo, @Nullable MethodNode target, Opcode opcode) {
				if (mthInfo.getName().equals("registerReceiver")) {
					registerReceiverUsed = true;
				}
				if (target != null) {
					addEdge(edges, target);
					if (isVirtualCall(opcode)) {
						addOverrides(edges, target);
					}
				}
			}

			@Override
			public void callSite(MethodInfo mthInfo, @Nullable MethodNode target) {
				if (target != null) {
					addEdge(edges, target);
				}
			}

			@Override
			public void newInstance(ClassNode cls) {
				instantiated.add(cls);
				for (MethodNode callback : getCallbacks(cls)) {
					addEdge(edges, callback);
				}
			}
		});
	}

	private static boolean isVirtualCall(Opcode opcode) {
		switch (opcode) {
			case INVOKE_VIRTUAL:
			case INVOKE_VIRTUAL_RANGE:
			case INVOKE_INTERFACE:
			case INVOKE_INTERFACE_RANGE:
				return true;
			default:
				return false;
		}
	}

	/**
	 * Add methods in subclasses which override the called method
	 */
	private void addOverrides(Set<Integer> edges, MethodNode target) {
		MethodOverrideAttr attr = target.get(AType.METHOD_OVERRIDE);
		if (attr == null) {
			return;
		}
		ClspGraph clsp = root.getClsp();
		String targetCls = target.getParentClass().getRawName();
		for (MethodNode related : attr.getRelatedMthNodes()) {
			if (related != target && clsp.isImplements(related.getParentClass().getRawName(), targetCls)) {
				addEdge(edges, related);
			}
		}
	}

	/**
	 * Methods of the class (and its app superclasses) which override framework methods.
	 * These are called by the framework after the object was created and passed somewhere.
	 */
	private List<MethodNode> getCallbacks(ClassNode cls) {
		return callbacksCache.computeIfAbsent(cls, c -> {
			List<MethodNode> list = new ArrayList<>();
			ClassNode current = c;
			while (current != null) {
				for (MethodNode mth : current.getMethods()) {
					if (!mth.getMethodInfo().isConstructor() && overridesFrameworkMethod(mth)) {
						list.add(mth);
					}
				}
				current = getAppSuperClass(current);
			}
			return list;
		});
	}

	private static boolean overridesFrameworkMethod(MethodNode mth) {
		MethodOverrideAttr attr = mth.get(AType.METHOD_OVERRIDE);
		if (attr == null) {
			return false;
		}
		for (IMethodDetails details : attr.getOverrideList()) {
			if (!(details instanceof MethodNode)) {
				return true;
			}
		}
		for (IMethodDetails details : attr.getBaseMethods()) {
			if (!(details instanceof MethodNode)) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	ClassNode getAppSuperClass(ClassNode cls) {
		ArgType superClass = cls.getSuperClass();
		if (superClass == null) {
			return null;
		}
		return root.resolveClass(superClass);
	}

	private void addEdge(Set<Integer> edges, MethodNode target) {
		Integer id = ids.get(target);
		if (id != null) {
			edges.add(id);
		}
	}

	/**
	 * Compute transitive API usage for every method.
	 * Iterative Tarjan SCC: components are completed in reverse topological order,
	 * so all successor components are already computed when a component is finished.
	 */
	private void computeReach() {
		int n = methods.size();
		reach = new BitSet[n];
		int[] index = new int[n];
		int[] low = new int[n];
		int[] edgePos = new int[n];
		boolean[] onStack = new boolean[n];
		Arrays.fill(index, -1);
		int[] stack = new int[n];
		int sp = 0;
		int[] callStack = new int[n];
		int counter = 0;
		BitSet empty = new BitSet();

		for (int start = 0; start < n; start++) {
			if (index[start] != -1) {
				continue;
			}
			int csp = 0;
			callStack[csp++] = start;
			index[start] = counter;
			low[start] = counter++;
			stack[sp++] = start;
			onStack[start] = true;
			edgePos[start] = 0;
			while (csp > 0) {
				int v = callStack[csp - 1];
				int[] vs = succ[v];
				if (edgePos[v] < vs.length) {
					int w = vs[edgePos[v]++];
					if (index[w] == -1) {
						index[w] = counter;
						low[w] = counter++;
						stack[sp++] = w;
						onStack[w] = true;
						edgePos[w] = 0;
						callStack[csp++] = w;
					} else if (onStack[w]) {
						low[v] = Math.min(low[v], index[w]);
					}
					continue;
				}
				csp--;
				if (csp > 0) {
					int parent = callStack[csp - 1];
					low[parent] = Math.min(low[parent], low[v]);
				}
				if (low[v] == index[v]) {
					// pop component
					List<Integer> members = new ArrayList<>();
					int w;
					do {
						w = stack[--sp];
						onStack[w] = false;
						members.add(w);
					} while (w != v);
					BitSet bits = new BitSet();
					for (int m : members) {
						if (direct[m] != null) {
							bits.or(direct[m]);
						}
						for (int s : succ[m]) {
							BitSet sr = reach[s];
							if (sr != null) {
								// successor in already completed component (members of current one are still null)
								bits.or(sr);
							}
						}
					}
					BitSet result = bits.isEmpty() ? empty : bits;
					for (int m : members) {
						reach[m] = result;
					}
				}
			}
		}
	}

	public RootNode getRoot() {
		return root;
	}

	public PrivacyRules getRules() {
		return rules;
	}

	public boolean isRegisterReceiverUsed() {
		return registerReceiverUsed;
	}

	public boolean isInstantiated(ClassNode cls) {
		return instantiated.contains(cls);
	}

	public static boolean isBroadcastReceiver(ClassNode cls) {
		return cls.root().getClsp().isImplements(cls.getRawName(), BROADCAST_RECEIVER);
	}

	public BitSet getDirectHits(MethodNode mth) {
		Integer id = ids.get(mth);
		if (id == null || direct[id] == null) {
			return new BitSet();
		}
		return direct[id];
	}

	public BitSet getReachHits(MethodNode mth) {
		Integer id = ids.get(mth);
		if (id == null || reach[id] == null) {
			return new BitSet();
		}
		return reach[id];
	}

	/**
	 * Find call paths from root methods to the methods which directly use the API.
	 *
	 * @return shortest path (root method first, call site last) for each call site, at most
	 *         {@code limit}
	 */
	public List<List<MethodNode>> findCallPaths(Collection<MethodNode> roots, int apiIndex, int limit) {
		int n = methods.size();
		int[] parent = new int[n];
		Arrays.fill(parent, -2);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		for (MethodNode r : roots) {
			Integer id = ids.get(r);
			if (id != null && parent[id] == -2) {
				parent[id] = -1;
				queue.add(id);
			}
		}
		List<List<MethodNode>> paths = new ArrayList<>();
		while (!queue.isEmpty() && paths.size() < limit) {
			int v = queue.poll();
			BitSet reachBits = reach[v];
			if (reachBits == null || !reachBits.get(apiIndex)) {
				continue; // API not reachable from here
			}
			if (direct[v] != null && direct[v].get(apiIndex)) {
				List<MethodNode> path = new ArrayList<>();
				for (int p = v; p != -1; p = parent[p]) {
					path.add(methods.get(p));
				}
				Collections.reverse(path);
				paths.add(path);
			}
			for (int w : succ[v]) {
				if (parent[w] == -2) {
					parent[w] = v;
					queue.add(w);
				}
			}
		}
		return paths;
	}

	/**
	 * Indexes of all APIs used anywhere in the app
	 */
	public BitSet getUsedApis() {
		BitSet used = new BitSet();
		for (BitSet bits : direct) {
			if (bits != null) {
				used.or(bits);
			}
		}
		return used;
	}

	/**
	 * Methods which use the API directly
	 */
	public List<MethodNode> getDirectUsers(int apiIndex) {
		List<MethodNode> list = new ArrayList<>();
		for (int i = 0; i < direct.length; i++) {
			if (direct[i] != null && direct[i].get(apiIndex)) {
				list.add(methods.get(i));
			}
		}
		return list;
	}

	/**
	 * Find shortest call paths from entry methods to the target method (reverse search from the
	 * target, nearest entries first).
	 *
	 * @return paths with an entry method first and the target last, at most one path per entry
	 *         method and at most {@code limit} paths
	 */
	public List<List<MethodNode>> findPathsTo(MethodNode target, Set<MethodNode> entries, int limit) {
		Integer targetId = ids.get(target);
		if (targetId == null) {
			return Collections.emptyList();
		}
		int[][] callers = getPredecessors();
		int[] next = new int[methods.size()];
		Arrays.fill(next, -2);
		next[targetId] = -1;
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		queue.add(targetId);
		List<List<MethodNode>> paths = new ArrayList<>();
		while (!queue.isEmpty() && paths.size() < limit) {
			int v = queue.poll();
			MethodNode mth = methods.get(v);
			if (entries.contains(mth)) {
				List<MethodNode> path = new ArrayList<>();
				for (int p = v; p != -1; p = next[p]) {
					path.add(methods.get(p));
				}
				paths.add(path);
			}
			for (int w : callers[v]) {
				if (next[w] == -2) {
					next[w] = v;
					queue.add(w);
				}
			}
		}
		return paths;
	}

	private synchronized int[][] getPredecessors() {
		if (pred == null) {
			int n = methods.size();
			int[] counts = new int[n];
			for (int[] s : succ) {
				for (int w : s) {
					counts[w]++;
				}
			}
			int[][] result = new int[n][];
			for (int i = 0; i < n; i++) {
				result[i] = new int[counts[i]];
			}
			int[] pos = new int[n];
			for (int v = 0; v < n; v++) {
				for (int w : succ[v]) {
					result[w][pos[w]++] = v;
				}
			}
			pred = result;
		}
		return pred;
	}

	@Override
	public String toString() {
		return "PrivacyCallGraph{methods=" + methods.size() + '}';
	}
}
