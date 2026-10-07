package jadx.permdb;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jetbrains.annotations.Nullable;

import jadx.permdb.PermissionData.ApiKind;

/**
 * Axplorer permission maps ({@code sdk-map-NN.txt}, API 16-25): permission checks found by static
 * analysis of the framework, including checks the documentation doesn't mention.
 *
 * <pre>
 * android.app.KeyguardManager$KeyguardLock.disableKeyguard()void  ::  android.permission.DISABLE_KEYGUARD
 * </pre>
 *
 * Permissions of one entry are all checked on the call path ('all').
 * The maps also list inherited methods for every subclass (e.g. {@code Context} methods for each
 * {@code Activity}), these duplicates are removed if the super class has the same entry.
 */
final class AxplorerParser {
	static final String SOURCE = "axplorer";

	static final class Entry {
		final String cls;
		final String method;
		final String params;
		final Set<String> permissions;

		Entry(String cls, String method, String params, Set<String> permissions) {
			this.cls = cls;
			this.method = method;
			this.params = params;
			this.permissions = permissions;
		}

		String memberKey() {
			return method + '(' + params + ')' + permissions;
		}
	}

	private AxplorerParser() {
	}

	static List<Entry> parse(byte[] content) {
		List<Entry> list = new ArrayList<>();
		for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
			Entry entry = parseLine(line.trim());
			if (entry != null) {
				list.add(entry);
			}
		}
		return list;
	}

	static @Nullable Entry parseLine(String line) {
		int sep = line.indexOf("::");
		if (sep == -1) {
			return null;
		}
		String sig = line.substring(0, sep).trim();
		int open = sig.indexOf('(');
		int close = sig.indexOf(')', open);
		if (open == -1 || close == -1) {
			return null;
		}
		String clsAndName = sig.substring(0, open);
		int dot = clsAndName.lastIndexOf('.');
		if (dot == -1) {
			return null;
		}
		String cls = clsAndName.substring(0, dot);
		String name = clsAndName.substring(dot + 1);
		List<String> paramList = new ArrayList<>();
		for (String p : sig.substring(open + 1, close).split(",")) {
			if (!p.isBlank()) {
				paramList.add(JavaNames.eraseType(p.trim()));
			}
		}
		Set<String> perms = new TreeSet<>();
		for (String perm : line.substring(sep + 2).split(",")) {
			if (!perm.isBlank()) {
				perms.add(perm.trim());
			}
		}
		if (perms.isEmpty()) {
			return null;
		}
		return new Entry(cls, name, String.join(",", paramList), perms);
	}

	/**
	 * Add entries of one API level, skipping entries inherited from a super class with the same
	 * permissions
	 */
	/**
	 * Remove permissions which are found on internal call paths of many methods
	 *
	 * @param noise permission -> text the method name must contain to keep it
	 */
	static List<Entry> removeNoise(List<Entry> entries, Map<String, String> noise) {
		List<Entry> list = new ArrayList<>(entries.size());
		for (Entry e : entries) {
			Set<String> perms = new TreeSet<>();
			for (String perm : e.permissions) {
				String keepIf = noise.get(perm);
				if (keepIf == null || e.method.contains(keepIf)) {
					perms.add(perm);
				}
			}
			if (!perms.isEmpty()) {
				list.add(new Entry(e.cls, e.method, e.params, perms));
			}
		}
		return list;
	}

	static void apply(PermissionData data, List<Entry> entries, int level, StubsParser stubs) {
		Map<String, Set<String>> byClass = new HashMap<>();
		for (Entry e : entries) {
			byClass.computeIfAbsent(e.cls, c -> new HashSet<>()).add(e.memberKey());
		}
		for (Entry e : entries) {
			if (isInherited(e, byClass, stubs)) {
				continue;
			}
			PermissionData.Api api = data.getOrAddApi(ApiKind.METHOD, e.cls, e.method, e.params);
			for (String perm : e.permissions) {
				data.addRequirement(api, perm, "all", false, null, SOURCE, level);
			}
		}
	}

	private static boolean isInherited(Entry e, Map<String, Set<String>> byClass, StubsParser stubs) {
		String key = e.memberKey();
		Set<String> visited = new HashSet<>();
		String superCls = stubs.getSuperClass(e.cls);
		while (superCls != null && visited.add(superCls)) {
			Set<String> superEntries = byClass.get(superCls);
			if (superEntries != null && superEntries.contains(key)) {
				return true;
			}
			superCls = stubs.getSuperClass(superCls);
		}
		return false;
	}
}
