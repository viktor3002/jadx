package jadx.permdb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

import org.jetbrains.annotations.Nullable;

/**
 * Collected data, written by {@link DbWriter}. All collections are sorted to make the output
 * deterministic.
 */
final class PermissionData {

	enum ApiKind {
		METHOD("method"), FIELD("field");

		private final String dbName;

		ApiKind(String dbName) {
			this.dbName = dbName;
		}

		String getDbName() {
			return dbName;
		}
	}

	/**
	 * Framework method or field.
	 * Class names use the binary form ({@code android.provider.Telephony$Sms}), constructors have
	 * the name {@code <init>}, parameters are erased Java types separated by ',' (null for fields).
	 */
	static final class Api {
		private final ApiKind kind;
		private final String cls;
		private final String member;
		private final @Nullable String params;
		/** value of a String constant field (constants are inlined in app code) */
		private @Nullable String constant;
		private final SortedMap<String, Requirement> requirements = new TreeMap<>();

		Api(ApiKind kind, String cls, String member, @Nullable String params) {
			this.kind = kind;
			this.cls = cls;
			this.member = member;
			this.params = params;
		}

		ApiKind getKind() {
			return kind;
		}

		String getCls() {
			return cls;
		}

		String getMember() {
			return member;
		}

		@Nullable
		String getParams() {
			return params;
		}

		@Nullable
		String getConstant() {
			return constant;
		}

		void setConstant(@Nullable String constant) {
			this.constant = constant;
		}

		Collection<Requirement> getRequirements() {
			return requirements.values();
		}

		String getKey() {
			return key(kind, cls, member, params);
		}

		static String key(ApiKind kind, String cls, String member, @Nullable String params) {
			return kind.getDbName() + ' ' + cls + '.' + member + (params == null ? "" : '(' + params + ')');
		}
	}

	/**
	 * One permission required by an API, with the API levels in which the requirement is
	 * documented.
	 */
	static final class Requirement {
		private final String permission;
		/** 'all' (all permissions of this source are needed) or 'any' (one of them is enough) */
		private final String mode;
		/** permission is only needed in some cases (e.g. depending on arguments) */
		private final boolean conditional;
		/** 'read' / 'write' for content URIs, null otherwise */
		private final @Nullable String access;
		private final String source;
		private @Nullable Integer minApi;
		private @Nullable Integer maxApi;

		Requirement(String permission, String mode, boolean conditional, @Nullable String access, String source) {
			this.permission = permission;
			this.mode = mode;
			this.conditional = conditional;
			this.access = access;
			this.source = source;
		}

		String getPermission() {
			return permission;
		}

		String getMode() {
			return mode;
		}

		boolean isConditional() {
			return conditional;
		}

		@Nullable
		String getAccess() {
			return access;
		}

		String getSource() {
			return source;
		}

		@Nullable
		Integer getMinApi() {
			return minApi;
		}

		@Nullable
		Integer getMaxApi() {
			return maxApi;
		}

		void addLevel(@Nullable Integer level) {
			if (level == null) {
				return;
			}
			minApi = minApi == null ? level : Math.min(minApi, level);
			maxApi = maxApi == null ? level : Math.max(maxApi, level);
		}

		String getKey() {
			return permission + ' ' + mode + ' ' + conditional + ' ' + access + ' ' + source;
		}
	}

	static final class PermissionInfo {
		private final String name;
		private final @Nullable String protectionLevel;
		private final @Nullable String group;
		private final @Nullable String flags;
		private final String source;

		PermissionInfo(String name, @Nullable String protectionLevel, @Nullable String group, @Nullable String flags, String source) {
			this.name = name;
			this.protectionLevel = protectionLevel;
			this.group = group;
			this.flags = flags;
			this.source = source;
		}

		String getName() {
			return name;
		}

		@Nullable
		String getProtectionLevel() {
			return protectionLevel;
		}

		@Nullable
		String getGroup() {
			return group;
		}

		@Nullable
		String getFlags() {
			return flags;
		}

		String getSource() {
			return source;
		}
	}

	/**
	 * Permission needed to read or write a content provider (or a path of it)
	 */
	static final class ProviderPermission implements Comparable<ProviderPermission> {
		private final String authority;
		/** empty for the whole provider */
		private final String path;
		/** 'path', 'prefix' or 'pattern', empty for the whole provider */
		private final String pathType;
		private final String access;
		private final String permission;
		private final @Nullable String pkg;
		private final String source;

		ProviderPermission(String authority, String path, String pathType, String access, String permission,
				@Nullable String pkg, String source) {
			this.authority = authority;
			this.path = path;
			this.pathType = pathType;
			this.access = access;
			this.permission = permission;
			this.pkg = pkg;
			this.source = source;
		}

		String getAuthority() {
			return authority;
		}

		String getPath() {
			return path;
		}

		String getPathType() {
			return pathType;
		}

		String getAccess() {
			return access;
		}

		String getPermission() {
			return permission;
		}

		@Nullable
		String getPkg() {
			return pkg;
		}

		String getSource() {
			return source;
		}

		String getKey() {
			return authority + ' ' + path + ' ' + access + ' ' + permission;
		}

		@Override
		public int compareTo(ProviderPermission o) {
			return getKey().compareTo(o.getKey());
		}
	}

	static final class Broadcast {
		private final String action;
		/** permission the receiving app needs (the broadcast is sent with it), null if none */
		private @Nullable String permission;
		/** only the system can send it ({@code <protected-broadcast>}) */
		private boolean protectedBroadcast;
		private String source;

		Broadcast(String action, String source) {
			this.action = action;
			this.source = source;
		}

		String getAction() {
			return action;
		}

		@Nullable
		String getPermission() {
			return permission;
		}

		void setPermission(@Nullable String permission, String newSource) {
			this.permission = permission;
			this.source = newSource;
		}

		boolean isProtectedBroadcast() {
			return protectedBroadcast;
		}

		void setProtectedBroadcast(boolean protectedBroadcast) {
			this.protectedBroadcast = protectedBroadcast;
		}

		String getSource() {
			return source;
		}
	}

	static final class Source {
		private final String name;
		private final String url;
		private final String revision;

		Source(String name, String url, String revision) {
			this.name = name;
			this.url = url;
			this.revision = revision;
		}

		String getName() {
			return name;
		}

		String getUrl() {
			return url;
		}

		String getRevision() {
			return revision;
		}
	}

	private final SortedMap<String, Api> apis = new TreeMap<>();
	private final SortedMap<String, PermissionInfo> permissions = new TreeMap<>();
	private final SortedMap<String, ProviderPermission> providers = new TreeMap<>();
	private final SortedMap<String, Broadcast> broadcasts = new TreeMap<>();
	private final SortedMap<String, Source> sources = new TreeMap<>();
	private final SortedMap<String, String> meta = new TreeMap<>();

	Api getOrAddApi(ApiKind kind, String cls, String member, @Nullable String params) {
		return apis.computeIfAbsent(Api.key(kind, cls, member, params), k -> new Api(kind, cls, member, params));
	}

	@Nullable
	Api getApi(ApiKind kind, String cls, String member, @Nullable String params) {
		return apis.get(Api.key(kind, cls, member, params));
	}

	/**
	 * Add requirement or extend its API level range
	 */
	void addRequirement(Api api, String permission, String mode, boolean conditional, @Nullable String access,
			String source, @Nullable Integer level) {
		Requirement req = new Requirement(permission, mode, conditional, access, source);
		api.requirements.computeIfAbsent(req.getKey(), k -> req).addLevel(level);
	}

	void addPermission(PermissionInfo info) {
		permissions.put(info.getName(), info);
	}

	void addProvider(ProviderPermission perm) {
		providers.putIfAbsent(perm.getKey(), perm);
	}

	Broadcast getOrAddBroadcast(String action, String source) {
		return broadcasts.computeIfAbsent(action, a -> new Broadcast(a, source));
	}

	void addSource(Source source) {
		sources.put(source.getName(), source);
	}

	void putMeta(String key, String value) {
		meta.put(key, value);
	}

	/**
	 * APIs with at least one permission requirement
	 */
	List<Api> getApis() {
		List<Api> list = new ArrayList<>(apis.size());
		for (Api api : apis.values()) {
			if (!api.requirements.isEmpty()) {
				list.add(api);
			}
		}
		return list;
	}

	Collection<PermissionInfo> getPermissions() {
		return permissions.values();
	}

	Collection<ProviderPermission> getProviders() {
		return providers.values();
	}

	Collection<Broadcast> getBroadcasts() {
		return broadcasts.values();
	}

	Collection<Source> getSources() {
		return sources.values();
	}

	Map<String, String> getMeta() {
		return Collections.unmodifiableMap(meta);
	}

	/**
	 * Canonical text form of the content (without meta data and source revisions), used for change
	 * detection and the change log
	 */
	List<String> canonicalLines() {
		List<String> lines = new ArrayList<>();
		for (PermissionInfo p : permissions.values()) {
			lines.add(join("permission", p.getName(), p.getProtectionLevel(), p.getGroup(), p.getFlags()));
		}
		for (Api api : getApis()) {
			for (Requirement r : api.getRequirements()) {
				lines.add(join("api", api.getKey(), api.getConstant(), r.getPermission(), r.getMode(),
						r.isConditional() ? "conditional" : "", r.getAccess(), str(r.getMinApi()), str(r.getMaxApi()), r.getSource()));
			}
		}
		for (ProviderPermission p : providers.values()) {
			lines.add(join("provider", p.getAuthority(), p.getPath(), p.getPathType(), p.getAccess(), p.getPermission(), p.getSource()));
		}
		for (Broadcast b : broadcasts.values()) {
			lines.add(join("broadcast", b.getAction(), b.getPermission(), b.isProtectedBroadcast() ? "protected" : "", b.getSource()));
		}
		Collections.sort(lines);
		return lines;
	}

	private static String str(@Nullable Integer value) {
		return value == null ? "" : value.toString();
	}

	static String join(String... parts) {
		StringBuilder sb = new StringBuilder();
		for (String part : parts) {
			if (sb.length() > 0) {
				sb.append('\t');
			}
			sb.append(Objects.toString(part, ""));
		}
		return sb.toString();
	}
}
