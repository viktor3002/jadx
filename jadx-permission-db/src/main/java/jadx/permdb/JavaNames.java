package jadx.permdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Convert Java source type names to the binary form used in jadx
 * ({@code android.app.Notification$Builder}).
 */
final class JavaNames {
	private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

	private JavaNames() {
	}

	/**
	 * Dotted class name to binary name. Nested classes are detected by naming convention: the first
	 * segment starting with an upper case letter is the top level class, following segments are
	 * nested classes.
	 */
	static String toBinaryName(String dotted) {
		String[] parts = dotted.split("\\.");
		StringBuilder sb = new StringBuilder(dotted.length());
		boolean inClass = false;
		for (int i = 0; i < parts.length; i++) {
			String part = parts[i];
			if (i > 0) {
				sb.append(inClass ? '$' : '.');
			}
			sb.append(part);
			if (!part.isEmpty() && Character.isUpperCase(part.charAt(0))) {
				inClass = true;
			}
		}
		return sb.toString();
	}

	/**
	 * Erase a source type: remove generics, varargs to array, binary class names.
	 * Type variables (like {@code T}) are kept as is.
	 */
	static String eraseType(String type) {
		String t = removeGenerics(type).trim();
		if (t.endsWith("...")) {
			t = t.substring(0, t.length() - 3) + "[]";
		}
		int arr = t.indexOf('[');
		String base = arr == -1 ? t : t.substring(0, arr);
		String dims = arr == -1 ? "" : t.substring(arr).replace(" ", "");
		base = base.trim();
		if (PRIMITIVES.contains(base) || base.indexOf('.') == -1) {
			return base + dims;
		}
		return toBinaryName(base) + dims;
	}

	/**
	 * Erase a comma separated source parameter list
	 */
	static String eraseParams(String params) {
		List<String> list = splitTopLevel(params);
		List<String> erased = new ArrayList<>(list.size());
		for (String p : list) {
			if (!p.isBlank()) {
				erased.add(eraseType(p));
			}
		}
		return String.join(",", erased);
	}

	static List<String> splitTopLevel(String params) {
		List<String> list = new ArrayList<>();
		int depth = 0;
		int start = 0;
		for (int i = 0; i < params.length(); i++) {
			char c = params.charAt(i);
			if (c == '<') {
				depth++;
			} else if (c == '>') {
				depth--;
			} else if (c == ',' && depth == 0) {
				list.add(params.substring(start, i).trim());
				start = i + 1;
			}
		}
		String last = params.substring(start).trim();
		if (!last.isEmpty()) {
			list.add(last);
		}
		return list;
	}

	static String removeGenerics(String type) {
		if (type.indexOf('<') == -1) {
			return type;
		}
		StringBuilder sb = new StringBuilder(type.length());
		int depth = 0;
		for (int i = 0; i < type.length(); i++) {
			char c = type.charAt(i);
			if (c == '<') {
				depth++;
			} else if (c == '>') {
				depth--;
			} else if (depth == 0) {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
