package jadx.gui.privacy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import jadx.core.clsp.ClspGraph;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.gui.privacy.PermissionDb.Requirement;
import jadx.gui.privacy.PrivacyRules.Api;

/**
 * Match method calls, field accesses and string constants against privacy and permission APIs
 */
public final class PrivacyApiMatcher {
	private static final String CONTENT_URI_PREFIX = "content://";

	/**
	 * Matched API, with the parameter types of the called overload (for methods)
	 */
	public static final class Hit {
		private final Api api;
		private final @Nullable String params;

		Hit(Api api, @Nullable String params) {
			this.api = api;
			this.params = params;
		}

		public Api getApi() {
			return api;
		}

		/**
		 * Erased parameter types of the called method separated by ',', null for fields and strings
		 */
		public @Nullable String getParams() {
			return params;
		}

		public List<Requirement> getRequirements() {
			return api.getRequirements(params);
		}
	}

	private final ClspGraph clsp;
	private final Map<String, List<Api>> methodsByName = new HashMap<>();
	private final List<Api> wildcardMethods = new ArrayList<>();
	private final Map<String, List<Api>> fieldsByName = new HashMap<>();
	private final Map<String, Api> exactStrings = new HashMap<>();
	private final List<Api> prefixStrings = new ArrayList<>();

	/**
	 * @param manifest if not null, only APIs of capabilities declared in this manifest are matched
	 *                 (others are treated as dead code)
	 */
	public PrivacyApiMatcher(PrivacyRules rules, @Nullable ManifestInfo manifest, ClspGraph clsp) {
		this.clsp = clsp;
		for (Api api : rules.getApis()) {
			if (manifest != null && !PrivacyRules.isActive(api.getCapability(), manifest)) {
				continue;
			}
			switch (api.getKind()) {
				case METHOD:
					if (api.getName().equals("*")) {
						wildcardMethods.add(api);
					} else {
						methodsByName.computeIfAbsent(api.getName(), k -> new ArrayList<>()).add(api);
					}
					break;
				case FIELD:
					fieldsByName.computeIfAbsent(api.getName(), k -> new ArrayList<>()).add(api);
					break;
				case STRING:
					if (api.isExact()) {
						exactStrings.putIfAbsent(api.getName(), api);
					} else {
						prefixStrings.add(api);
					}
					break;
			}
		}
	}

	public @Nullable Hit matchMethod(MethodInfo mthInfo) {
		String cls = mthInfo.getDeclClass().getRawName();
		List<Api> list = methodsByName.get(mthInfo.getName());
		if (list != null) {
			// constructors are not inherited (the super constructor call is matched itself)
			boolean constructor = mthInfo.isConstructor();
			String params = null;
			for (Api api : list) {
				if (constructor ? !cls.equals(api.getCls()) : !isSubclass(cls, api.getCls())) {
					continue;
				}
				Set<String> overloads = api.getOverloads();
				if (overloads == null) {
					return new Hit(api, null);
				}
				if (params == null) {
					params = params(mthInfo.getArgumentsTypes());
				}
				String matched = matchOverload(overloads, params);
				if (matched != null) {
					return new Hit(api, matched);
				}
			}
		}
		for (Api api : wildcardMethods) {
			if (isSubclass(cls, api.getCls())) {
				return new Hit(api, null);
			}
		}
		return null;
	}

	public @Nullable Hit matchField(String cls, String name) {
		List<Api> list = fieldsByName.get(name);
		if (list == null) {
			return null;
		}
		for (Api api : list) {
			if (isSubclass(cls, api.getCls())) {
				return new Hit(api, null);
			}
		}
		return null;
	}

	public @Nullable Hit matchString(@Nullable String str) {
		if (str == null || str.isEmpty()) {
			return null;
		}
		Api exact = exactStrings.get(str);
		if (exact != null) {
			return new Hit(exact, null);
		}
		for (Api api : prefixStrings) {
			String prefix = api.getName();
			if (str.startsWith(prefix) && isPrefixBoundary(str, prefix)) {
				return new Hit(api, null);
			}
		}
		return null;
	}

	/**
	 * Content URIs must match the whole authority: 'content://mms' doesn't match
	 * 'content://mms-sms/...'
	 */
	private static boolean isPrefixBoundary(String str, String prefix) {
		if (!prefix.startsWith(CONTENT_URI_PREFIX) || str.length() == prefix.length()) {
			return true;
		}
		char next = str.charAt(prefix.length());
		return next == '/' || next == '?' || next == '#';
	}

	private boolean isSubclass(String cls, String apiCls) {
		return cls.equals(apiCls) || clsp.isImplements(cls, apiCls);
	}

	private static @Nullable String matchOverload(Set<String> overloads, String params) {
		if (overloads.contains(params)) {
			return params;
		}
		// type variables in framework signatures (like 'T') match any object
		String[] args = params.isEmpty() ? new String[0] : params.split(",");
		for (String overload : overloads) {
			String[] expected = overload.isEmpty() ? new String[0] : overload.split(",");
			if (expected.length == args.length && matchTypes(expected, args)) {
				return overload;
			}
		}
		return null;
	}

	private static boolean matchTypes(String[] expected, String[] args) {
		for (int i = 0; i < expected.length; i++) {
			String e = expected[i];
			if (!e.equals(args[i]) && !(isTypeVariable(e) && !isPrimitive(args[i]))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isTypeVariable(String type) {
		String base = type.endsWith("[]") ? type.substring(0, type.indexOf('[')) : type;
		return base.indexOf('.') == -1 && !isPrimitive(base) && !base.isEmpty() && Character.isUpperCase(base.charAt(0));
	}

	private static boolean isPrimitive(String type) {
		switch (type) {
			case "boolean":
			case "byte":
			case "char":
			case "short":
			case "int":
			case "long":
			case "float":
			case "double":
				return true;
			default:
				return false;
		}
	}

	/**
	 * Erased parameter types in the database format:
	 * {@code int,java.lang.String[],android.app.Notification$Builder}
	 */
	public static String params(List<ArgType> args) {
		StringBuilder sb = new StringBuilder();
		for (ArgType arg : args) {
			if (sb.length() > 0) {
				sb.append(',');
			}
			sb.append(typeName(arg));
		}
		return sb.toString();
	}

	private static String typeName(ArgType type) {
		if (type.isPrimitive()) {
			return type.getPrimitiveType().getLongName();
		}
		if (type.isArray()) {
			return typeName(type.getArrayElement()) + "[]";
		}
		if (type.isObject()) {
			return type.getObject();
		}
		return type.toString();
	}
}
