package jadx.gui.privacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import jadx.api.ICodeInfo;
import jadx.api.metadata.ICodeMetadata;
import jadx.api.metadata.ICodeNodeRef;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.gui.privacy.PrivacyApiMatcher.Hit;
import jadx.gui.privacy.PrivacyRules.Api;
import jadx.gui.privacy.PrivacyRules.ApiKind;

/**
 * Find usages of privacy and permission APIs in decompiled class code.
 * <br>
 * Calls to framework methods have no code metadata, so API usage is detected from the method
 * instructions and then located in the code by name, limited to the method which uses the API
 * (code of inlined lambdas is attributed to the enclosing method).
 */
public final class PrivacyCodeMarks {

	public static final class Mark {
		private final int start;
		private final int end;
		private final Hit hit;

		Mark(int start, int end, Hit hit) {
			this.start = start;
			this.end = end;
			this.hit = hit;
		}

		public int getStart() {
			return start;
		}

		public int getEnd() {
			return end;
		}

		public Api getApi() {
			return hit.getApi();
		}

		public Hit getHit() {
			return hit;
		}

		/**
		 * API needs a privacy relevant permission (counted in the privacy score)
		 */
		public boolean isPrivacy() {
			return hit.getApi().getCapability().isPrivacy();
		}
	}

	/** API usages per method, by code token */
	private final Map<MethodNode, Map<String, Hit>> direct = new IdentityHashMap<>();
	/** inlined lambda methods called from method */
	private final Map<MethodNode, List<MethodNode>> lambdas = new IdentityHashMap<>();
	private final Map<MethodNode, Map<String, Hit>> effective = new IdentityHashMap<>();

	private PrivacyCodeMarks() {
	}

	/**
	 * @return marks sorted by start position. All APIs requiring a permission are marked (not the
	 *         'flag' capabilities), regardless of the permissions declared in the manifest.
	 */
	public static List<Mark> compute(ClassNode topCls, ICodeInfo codeInfo, PrivacyRules rules) {
		String code = codeInfo.getCodeStr();
		ICodeMetadata metadata = codeInfo.getCodeMetadata();
		if (code.isEmpty() || metadata == null) {
			return Collections.emptyList();
		}
		PrivacyCodeMarks marks = new PrivacyCodeMarks();
		marks.scanClass(topCls, rules);
		if (marks.direct.isEmpty()) {
			return Collections.emptyList();
		}
		return marks.locate(code, metadata);
	}

	private List<Mark> locate(String code, ICodeMetadata metadata) {
		Map<String, Boolean> tokens = new HashMap<>(); // token -> is string constant
		for (Map<String, Hit> map : direct.values()) {
			map.forEach((token, hit) -> tokens.put(token, hit.getApi().getKind() == ApiKind.STRING));
		}
		List<Mark> marks = new ArrayList<>();
		tokens.forEach((token, isString) -> {
			String search = isString ? '"' + token : token;
			int from = 0;
			while (true) {
				int pos = code.indexOf(search, from);
				if (pos == -1) {
					break;
				}
				from = pos + search.length();
				int start = isString ? pos + 1 : pos;
				if (!isString && !isIdentifierAt(code, start, token.length())) {
					continue;
				}
				Hit hit = findHit(metadata.getNodeAt(start), token);
				if (hit != null) {
					marks.add(new Mark(start, start + token.length(), hit));
				}
			}
		});
		marks.sort(Comparator.comparingInt(Mark::getStart));
		return marks;
	}

	private static boolean isIdentifierAt(String code, int start, int len) {
		if (start > 0 && Character.isJavaIdentifierPart(code.charAt(start - 1))) {
			return false;
		}
		int end = start + len;
		return end >= code.length() || !Character.isJavaIdentifierPart(code.charAt(end));
	}

	private @Nullable Hit findHit(@Nullable ICodeNodeRef node, String token) {
		if (node instanceof MethodNode) {
			return getEffective((MethodNode) node).get(token);
		}
		if (node instanceof ClassNode) {
			// field initializers are moved out of constructors and static init
			for (MethodNode mth : ((ClassNode) node).getMethods()) {
				MethodInfo mthInfo = mth.getMethodInfo();
				if (mthInfo.isConstructor() || mthInfo.isClassInit()) {
					Hit hit = getEffective(mth).get(token);
					if (hit != null) {
						return hit;
					}
				}
			}
		}
		return null;
	}

	/**
	 * Method own usages and usages of lambdas inlined into it
	 */
	private Map<String, Hit> getEffective(MethodNode mth) {
		Map<String, Hit> cached = effective.get(mth);
		if (cached != null) {
			return cached;
		}
		Set<MethodNode> inlined = new LinkedHashSet<>();
		collectInlined(mth, inlined);
		Map<String, Hit> result = new HashMap<>();
		for (MethodNode m : inlined) {
			Map<String, Hit> map = direct.get(m);
			if (map != null) {
				map.forEach(result::putIfAbsent);
			}
		}
		effective.put(mth, result);
		return result;
	}

	private void collectInlined(MethodNode mth, Set<MethodNode> visited) {
		if (visited.add(mth)) {
			for (MethodNode lambda : lambdas.getOrDefault(mth, Collections.emptyList())) {
				collectInlined(lambda, visited);
			}
		}
	}

	private void scanClass(ClassNode topCls, PrivacyRules rules) {
		Set<ClassNode> classes = new LinkedHashSet<>();
		classes.add(topCls);
		topCls.getInnerClassesRecursive(classes);
		PrivacyApiMatcher matcher = new PrivacyApiMatcher(rules, null, topCls.root().getClsp());
		PrivacyInsnScanner scanner = new PrivacyInsnScanner(topCls.root(), matcher);
		for (ClassNode cls : classes) {
			for (MethodNode mth : cls.getMethods()) {
				Map<String, Hit> found = new HashMap<>();
				List<MethodNode> mthLambdas = new ArrayList<>();
				scanner.scan(mth, new PrivacyInsnScanner.Visitor() {
					@Override
					public void api(Hit hit, String token) {
						if (hit.getApi().getCapability().getFlag() == null) {
							// privacy relevant usage wins if the same name is used several times
							found.merge(token, hit, (a, b) -> !a.getApi().getCapability().isPrivacy()
									&& b.getApi().getCapability().isPrivacy() ? b : a);
						}
					}

					@Override
					public void callSite(MethodInfo mthInfo, @Nullable MethodNode target) {
						if (target != null && (target.contains(AFlag.DONT_GENERATE) || target.getAccessFlags().isSynthetic())) {
							mthLambdas.add(target);
						}
					}
				});
				if (!found.isEmpty()) {
					direct.put(mth, found);
				}
				if (!mthLambdas.isEmpty()) {
					lambdas.put(mth, mthLambdas);
				}
			}
		}
	}
}
