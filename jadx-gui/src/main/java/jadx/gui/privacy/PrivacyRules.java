package jadx.gui.privacy;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jetbrains.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import jadx.core.utils.exceptions.JadxRuntimeException;
import jadx.core.utils.files.FileUtils;
import jadx.gui.privacy.PermissionDb.DbApi;
import jadx.gui.privacy.PermissionDb.Requirement;

/**
 * Privacy relevant capabilities and the Android APIs, fields, strings and manifest entries
 * which indicate their usage. Loaded from {@code /privacy/privacy-rules.json}, extended with all
 * APIs of the Android permission database ({@link PermissionDb}).
 * <br>
 * Database APIs are assigned to the rule capability of their permission (so they count in the
 * score), APIs with other permissions get a capability per permission set with score 0
 * ({@link Capability#isPrivacy()} is false): they are highlighted in code and listed in the
 * permission usages, but not shown in the overview.
 */
public final class PrivacyRules {
	public static final String RESOURCE = "/privacy/privacy-rules.json";

	public enum Requires {
		/** at least one of the capability permissions must be declared in {@code <uses-permission>} */
		USES_PERMISSION,
		/** at least one manifest component must be protected by one of the capability permissions */
		COMPONENT_PERMISSION,
		/** always active, the capability doesn't need a declared permission */
		NONE
	}

	public enum ApiKind {
		METHOD, FIELD, STRING
	}

	public static final class Capability {
		private final String id;
		private final String label;
		private final String shortLabel;
		private final int score;
		private final Requires requires;
		private final List<String> permissions;
		private final @Nullable String flag;
		private final boolean privacy;

		Capability(String id, String label, @Nullable String shortLabel, int score, Requires requires, List<String> permissions,
				@Nullable String flag,
				boolean privacy) {
			this.id = id;
			this.label = label;
			this.shortLabel = shortLabel != null ? shortLabel : label;
			this.score = score;
			this.requires = requires;
			this.permissions = permissions;
			this.flag = flag;
			this.privacy = privacy;
		}

		public String getId() {
			return id;
		}

		/**
		 * Abbreviation for crowded views (like LOC, A11Y)
		 */
		public String getShortLabel() {
			return shortLabel;
		}

		public String getLabel() {
			return label;
		}

		public int getScore() {
			return score;
		}

		public Requires getRequires() {
			return requires;
		}

		public List<String> getPermissions() {
			return permissions;
		}

		/**
		 * Short marker (like 'DEX', 'REFL') for capabilities which don't add to the score,
		 * but show that the call graph is incomplete.
		 */
		public @Nullable String getFlag() {
			return flag;
		}

		/**
		 * True for capabilities of the rule table (privacy relevant, shown in the overview),
		 * false for other permissions from the permission database
		 */
		public boolean isPrivacy() {
			return privacy;
		}
	}

	/**
	 * One API. Each API counts at most once per row.
	 * Overloaded methods are one API (matched by name), database APIs are restricted to the
	 * overloads which need a permission.
	 */
	public static final class Api {
		private final int index;
		private final ApiKind kind;
		/** raw class name for methods and fields */
		private final String cls;
		/** method or field name ('*' for any method), string prefix or value for strings */
		private final String name;
		private final Capability capability;
		/** string must be equal (constants), otherwise it is a prefix */
		private final boolean exact;
		/** method parameters -> requirements, null: all overloads (rule table APIs) */
		private @Nullable Map<String, List<Requirement>> overloads;
		private final List<Requirement> requirements = new ArrayList<>();

		Api(int index, ApiKind kind, String cls, String name, Capability capability, boolean exact) {
			this.index = index;
			this.kind = kind;
			this.cls = cls;
			this.name = name;
			this.capability = capability;
			this.exact = exact;
		}

		public int getIndex() {
			return index;
		}

		public ApiKind getKind() {
			return kind;
		}

		public String getCls() {
			return cls;
		}

		public String getName() {
			return name;
		}

		public Capability getCapability() {
			return capability;
		}

		public boolean isExact() {
			return exact;
		}

		/**
		 * @return overloads (erased parameter types separated by ',') which need a permission, null
		 *         for all overloads
		 */
		public @Nullable Set<String> getOverloads() {
			return overloads == null ? null : overloads.keySet();
		}

		/**
		 * All requirements of all overloads, sorted by source
		 */
		public List<Requirement> getRequirements() {
			return requirements;
		}

		/**
		 * Requirements of one overload (all if unknown)
		 */
		public List<Requirement> getRequirements(@Nullable String params) {
			if (params != null && overloads != null) {
				List<Requirement> list = overloads.get(params);
				if (list != null) {
					return list;
				}
			}
			return requirements;
		}

		/**
		 * All permissions any overload needs
		 */
		public Set<String> getPermissions() {
			Set<String> set = new TreeSet<>(capability.getPermissions());
			for (Requirement req : requirements) {
				set.add(req.getPermission());
			}
			return set;
		}

		/**
		 * Stable key, used to persist results
		 */
		public String getKey() {
			return key(kind, cls, name);
		}

		static String key(ApiKind kind, String cls, String name) {
			switch (kind) {
				case METHOD:
					return "m:" + cls + '.' + name;
				case FIELD:
					return "f:" + cls + '.' + name;
				default:
					return "s:" + name;
			}
		}

		public String getLabel() {
			switch (kind) {
				case METHOD:
					String mthName = name.equals("<init>") ? "new " + shortCls(cls) : shortCls(cls) + '.' + name;
					return mthName + "()";
				case FIELD:
					return shortCls(cls) + '.' + name;
				default:
					return '"' + name + '"';
			}
		}

		private static String shortCls(String rawCls) {
			int dot = rawCls.lastIndexOf('.');
			return (dot == -1 ? rawCls : rawCls.substring(dot + 1)).replace('$', '.');
		}
	}

	private static final String MANIFEST_PERMISSION_PREFIX = "p:";
	private static final String MANIFEST_ACTION_PREFIX = "a:";

	private final int version;
	private final Map<String, Capability> capabilities;
	private final List<Api> apis;
	private final Map<String, Api> apiByKey;
	private final Map<String, Capability> componentPermissions;
	private final Map<String, Capability> intentActions;
	private final @Nullable PermissionDb permissionDb;
	private String fingerprint = "custom";

	private PrivacyRules(int version, Map<String, Capability> capabilities, List<Api> apis,
			Map<String, Capability> componentPermissions, Map<String, Capability> intentActions,
			@Nullable PermissionDb permissionDb) {
		this.version = version;
		this.capabilities = capabilities;
		this.apis = apis;
		this.componentPermissions = componentPermissions;
		this.intentActions = intentActions;
		this.permissionDb = permissionDb;
		Map<String, Api> map = new HashMap<>();
		for (Api api : apis) {
			map.put(api.getKey(), api);
		}
		this.apiByKey = map;
	}

	private static volatile PrivacyRules defaultRules;

	public static PrivacyRules getDefault() {
		PrivacyRules rules = defaultRules;
		if (rules == null) {
			synchronized (PrivacyRules.class) {
				rules = defaultRules;
				if (rules == null) {
					rules = load(loadRulesJson(), PermissionDb.getDefault());
					defaultRules = rules;
				}
			}
		}
		return rules;
	}

	public static String loadRulesJson() {
		try (InputStream in = PrivacyRules.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new JadxRuntimeException("Resource not found: " + RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (JadxRuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new JadxRuntimeException("Failed to load privacy rules", e);
		}
	}

	/**
	 * Rule table only, without permission database
	 */
	public static PrivacyRules load(String content) {
		return load(content, null);
	}

	public static PrivacyRules load(String content, @Nullable PermissionDb db) {
		PrivacyRules rules = load(new StringReader(content), db);
		String hash = FileUtils.md5Sum(content.getBytes(StandardCharsets.UTF_8));
		rules.fingerprint = db == null ? hash : hash + '-' + db.getContentHash();
		return rules;
	}

	private static PrivacyRules load(Reader reader, @Nullable PermissionDb db) {
		RulesJson json = new Gson().fromJson(reader, RulesJson.class);
		Map<String, Capability> caps = new LinkedHashMap<>();
		for (CapabilityJson c : json.capabilities) {
			Requires requires = parseRequires(c.requires);
			List<String> perms = c.permissions == null ? Collections.emptyList() : c.permissions;
			caps.put(c.id, new Capability(c.id, c.label, c.shortLabel, c.score, requires, perms, c.flag, true));
		}
		List<Api> apis = new ArrayList<>(json.apis.size());
		for (ApiJson a : json.apis) {
			Capability cap = getCapability(caps, a.capability);
			int idx = apis.size();
			if (a.method != null) {
				int dot = a.method.lastIndexOf('.');
				apis.add(new Api(idx, ApiKind.METHOD, a.method.substring(0, dot), a.method.substring(dot + 1), cap, false));
			} else if (a.field != null) {
				int dot = a.field.lastIndexOf('.');
				apis.add(new Api(idx, ApiKind.FIELD, a.field.substring(0, dot), a.field.substring(dot + 1), cap, false));
			} else if (a.string != null) {
				apis.add(new Api(idx, ApiKind.STRING, "", a.string, cap, false));
			} else {
				throw new JadxRuntimeException("Privacy rule without method, field or string for capability " + a.capability);
			}
		}
		Map<String, Capability> compPerms = new HashMap<>();
		Map<String, Capability> actions = new HashMap<>();
		if (json.manifest != null) {
			if (json.manifest.componentPermissions != null) {
				json.manifest.componentPermissions.forEach((k, v) -> compPerms.put(k, getCapability(caps, v)));
			}
			if (json.manifest.intentActions != null) {
				json.manifest.intentActions.forEach((k, v) -> actions.put(k, getCapability(caps, v)));
			}
		}
		if (db != null) {
			new DbMerger(caps, apis, actions).merge(db);
		}
		return new PrivacyRules(json.version, caps, apis, compPerms, actions, db);
	}

	/**
	 * Add permission database APIs to the rule table
	 */
	private static final class DbMerger {
		private final Map<String, Capability> caps;
		private final List<Api> apis;
		private final Map<String, Capability> actions;
		private final Map<String, Api> byKey = new HashMap<>();
		/** permission -> rule capabilities using it */
		private final Map<String, List<Capability>> capsByPermission = new HashMap<>();

		DbMerger(Map<String, Capability> caps, List<Api> apis, Map<String, Capability> actions) {
			this.caps = caps;
			this.apis = apis;
			this.actions = actions;
			for (Api api : apis) {
				byKey.put(api.getKey(), api);
			}
			for (Capability cap : caps.values()) {
				if (cap.getFlag() == null) {
					for (String perm : cap.getPermissions()) {
						capsByPermission.computeIfAbsent(perm, p -> new ArrayList<>()).add(cap);
					}
				}
			}
		}

		void merge(PermissionDb db) {
			// rule table APIs: attach requirements of the rule permissions (shown in code annotation)
			for (Api api : apis) {
				for (String perm : api.getCapability().getPermissions()) {
					api.requirements.add(new Requirement(perm, true, false, null, null, null, PermissionDb.SOURCE_RULES));
				}
			}
			for (DbApi dbApi : db.getApis()) {
				if (dbApi.getKind() == PermissionDb.ApiKind.METHOD) {
					add(ApiKind.METHOD, dbApi.getCls(), dbApi.getMember(), false, dbApi.getParams(), dbApi.getRequirements());
				} else if (dbApi.getConstant() != null) {
					// constants are inlined by the compiler, match by value
					add(ApiKind.STRING, "", dbApi.getConstant(), true, null, dbApi.getRequirements());
					actions.putIfAbsent(dbApi.getConstant(), capabilityFor(dbApi.getRequirements()));
				} else {
					add(ApiKind.FIELD, dbApi.getCls(), dbApi.getMember(), false, null, dbApi.getRequirements());
				}
			}
			db.getProviders().forEach((authority, reqs) -> add(ApiKind.STRING, "", "content://" + authority, false, null, reqs));
			db.getBroadcastPermissions().forEach((action, perm) -> {
				Requirement req = new Requirement(perm, false, false, null, null, null, "curated");
				actions.putIfAbsent(action, capabilityFor(Collections.singletonList(req)));
			});
		}

		private void add(ApiKind kind, String cls, String name, boolean exact, @Nullable String params, List<Requirement> reqs) {
			String key = Api.key(kind, cls, name);
			Api api = byKey.get(key);
			if (api == null) {
				api = new Api(apis.size(), kind, cls, name, capabilityFor(reqs), exact);
				if (kind == ApiKind.METHOD) {
					api.overloads = new HashMap<>();
				}
				apis.add(api);
				byKey.put(key, api);
			}
			if (api.overloads != null && params != null) {
				api.overloads.computeIfAbsent(params, p -> new ArrayList<>()).addAll(reqs);
			}
			api.requirements.addAll(reqs);
		}

		/**
		 * Rule capability with the highest score which uses one of the permissions, else a
		 * capability for the permission set
		 */
		private Capability capabilityFor(List<Requirement> reqs) {
			Capability best = null;
			Set<String> perms = new TreeSet<>();
			for (Requirement req : reqs) {
				perms.add(req.getPermission());
				for (Capability cap : capsByPermission.getOrDefault(req.getPermission(), Collections.emptyList())) {
					if (best == null || cap.getScore() > best.getScore()) {
						best = cap;
					}
				}
			}
			if (best != null) {
				return best;
			}
			String id = "perm:" + String.join("|", perms);
			return caps.computeIfAbsent(id, i -> {
				Set<String> labels = new LinkedHashSet<>();
				for (String perm : perms) {
					labels.add(shortPermission(perm));
				}
				String label = String.join(" | ", labels);
				return new Capability(i, label, label, 0, Requires.USES_PERMISSION, new ArrayList<>(perms), null, false);
			});
		}
	}

	/**
	 * {@code android.permission.READ_SMS} -> {@code READ_SMS}
	 */
	public static String shortPermission(String permission) {
		if (permission.startsWith("android.permission.")) {
			return permission.substring("android.permission.".length());
		}
		return permission;
	}

	private static Capability getCapability(Map<String, Capability> caps, String id) {
		Capability cap = caps.get(id);
		if (cap == null) {
			throw new JadxRuntimeException("Unknown privacy capability: " + id);
		}
		return cap;
	}

	private static Requires parseRequires(@Nullable String str) {
		if (str == null) {
			return Requires.USES_PERMISSION;
		}
		switch (str) {
			case "uses-permission":
				return Requires.USES_PERMISSION;
			case "component-permission":
				return Requires.COMPONENT_PERMISSION;
			case "none":
				return Requires.NONE;
			default:
				throw new JadxRuntimeException("Unknown 'requires' value: " + str);
		}
	}

	public int getVersion() {
		return version;
	}

	/**
	 * Hash of the rules and the permission database content, used to detect outdated saved
	 * results
	 */
	public String getFingerprint() {
		return fingerprint;
	}

	public @Nullable PermissionDb getPermissionDb() {
		return permissionDb;
	}

	/**
	 * Capability for a hit key: API key or manifest key (see {@link #manifestPermissionKey(String)},
	 * {@link #manifestActionKey(String)})
	 */
	public @Nullable Capability getCapabilityForKey(String key) {
		if (key.startsWith(MANIFEST_PERMISSION_PREFIX)) {
			return componentPermissions.get(key.substring(MANIFEST_PERMISSION_PREFIX.length()));
		}
		if (key.startsWith(MANIFEST_ACTION_PREFIX)) {
			return intentActions.get(key.substring(MANIFEST_ACTION_PREFIX.length()));
		}
		Api api = apiByKey.get(key);
		return api == null ? null : api.getCapability();
	}

	public String getLabelForKey(String key) {
		if (key.startsWith(MANIFEST_PERMISSION_PREFIX)) {
			return "AndroidManifest.xml: android:permission=\"" + key.substring(MANIFEST_PERMISSION_PREFIX.length()) + '"';
		}
		if (key.startsWith(MANIFEST_ACTION_PREFIX)) {
			return "AndroidManifest.xml: <action android:name=\"" + key.substring(MANIFEST_ACTION_PREFIX.length()) + "\">";
		}
		Api api = apiByKey.get(key);
		return api == null ? key : api.getLabel();
	}

	public static boolean isManifestKey(String key) {
		return key.startsWith(MANIFEST_PERMISSION_PREFIX) || key.startsWith(MANIFEST_ACTION_PREFIX);
	}

	public static String manifestPermissionKey(String permission) {
		return MANIFEST_PERMISSION_PREFIX + permission;
	}

	public static String manifestActionKey(String action) {
		return MANIFEST_ACTION_PREFIX + action;
	}

	/**
	 * Sum of capability scores of all distinct hits
	 */
	public int score(Collection<String> hitKeys) {
		int score = 0;
		for (String key : hitKeys) {
			Capability cap = getCapabilityForKey(key);
			if (cap != null) {
				score += cap.getScore();
			}
		}
		return score;
	}

	public Map<String, Capability> getCapabilities() {
		return capabilities;
	}

	public @Nullable Capability getCapability(String id) {
		return capabilities.get(id);
	}

	public List<Api> getApis() {
		return apis;
	}

	public @Nullable Api getApi(String key) {
		return apiByKey.get(key);
	}

	public Map<String, Capability> getComponentPermissions() {
		return componentPermissions;
	}

	public Map<String, Capability> getIntentActions() {
		return intentActions;
	}

	/**
	 * Check if the app declares what the capability needs, otherwise its usage is treated as dead code.
	 */
	public static boolean isActive(Capability cap, ManifestInfo manifest) {
		switch (cap.getRequires()) {
			case NONE:
				return true;
			case COMPONENT_PERMISSION:
				for (String perm : cap.getPermissions()) {
					if (manifest.getComponentPermissions().contains(perm)) {
						return true;
					}
				}
				return false;
			case USES_PERMISSION:
			default:
				for (String perm : cap.getPermissions()) {
					if (manifest.getUsesPermissions().contains(perm)) {
						return true;
					}
				}
				return false;
		}
	}

	@SuppressWarnings("unused")
	private static final class RulesJson {
		int version;
		List<CapabilityJson> capabilities;
		List<ApiJson> apis;
		ManifestJson manifest;
	}

	@SuppressWarnings("unused")
	private static final class CapabilityJson {
		String id;
		String label;
		@SerializedName("short")
		String shortLabel;
		int score;
		String requires;
		List<String> permissions;
		String flag;
	}

	@SuppressWarnings("unused")
	private static final class ApiJson {
		String capability;
		String method;
		String field;
		String string;
	}

	@SuppressWarnings("unused")
	private static final class ManifestJson {
		Map<String, String> componentPermissions;
		Map<String, String> intentActions;
	}
}
