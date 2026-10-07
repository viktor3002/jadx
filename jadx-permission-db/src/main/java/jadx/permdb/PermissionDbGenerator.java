package jadx.permdb;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import jadx.permdb.PermissionData.Api;
import jadx.permdb.PermissionData.ApiKind;
import jadx.permdb.PermissionData.ProviderPermission;
import jadx.permdb.SdkRepository.Platform;

/**
 * Build the Android API → permission database (SQLite) used by jadx-gui.
 * <br>
 * Sources (all discovered automatically, so a rerun picks up new Android releases):
 * <ol>
 * <li>SDK platforms from the Android SDK repository manifest: {@code @RequiresPermission}
 * annotations of every stable API level (read with HTTP range requests, only ~250 KB per
 * level), String constants and content URI fields from the source stubs of the latest level</li>
 * <li>AOSP (latest release tag): permission definitions with protection levels, protected
 * broadcasts, content provider permissions</li>
 * <li>Axplorer permission maps (API 16-25, static)</li>
 * <li>curated supplement ({@code supplement.json})</li>
 * </ol>
 * The database is only rewritten if its content changed (see
 * {@link PermissionData#canonicalLines()}).
 */
public final class PermissionDbGenerator {
	private static final Logger LOG = LoggerFactory.getLogger(PermissionDbGenerator.class);

	static final String CURATED = "curated";
	static final String PROVIDER_URI = "provider-uri";

	private static final String DEFAULT_OUT = "jadx-gui/src/main/resources/privacy/android-permissions.db";
	private static final String DEFAULT_CACHE = "jadx-permission-db/build/download-cache";
	// SDK platforms contain permission annotations since API 26
	private static final int DEFAULT_MIN_API = 26;
	private static final Pattern RELEASE_TAG = Pattern.compile("\"(android-(\\d+)\\.(\\d+)\\.(\\d+)_r(\\d+))\"");

	private final Options options;
	private final Http http;
	private final Supplement supplement;
	private final PermissionData data = new PermissionData();

	static final class Options {
		Path out = Paths.get(DEFAULT_OUT);
		Path cache = Paths.get(DEFAULT_CACHE);
		@Nullable
		Path changelog;
		int minApi = DEFAULT_MIN_API;
		@Nullable
		String aospTag;
		boolean offline;
		boolean force;
	}

	public static void main(String[] args) {
		try {
			Options options = parseArgs(args);
			if (options == null) {
				return;
			}
			new PermissionDbGenerator(options).run();
		} catch (Exception e) {
			LOG.error("Permission database generation failed", e);
			System.exit(1);
		}
	}

	private static @Nullable Options parseArgs(String[] args) {
		Options o = new Options();
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			switch (arg) {
				case "--out":
					o.out = Paths.get(args[++i]);
					break;
				case "--cache":
					o.cache = Paths.get(args[++i]);
					break;
				case "--changelog":
					o.changelog = Paths.get(args[++i]);
					break;
				case "--min-api":
					o.minApi = Integer.parseInt(args[++i]);
					break;
				case "--aosp-tag":
					o.aospTag = args[++i];
					break;
				case "--offline":
					o.offline = true;
					break;
				case "--force":
					o.force = true;
					break;
				case "-h":
				case "--help":
					printUsage();
					return null;
				default:
					throw new IllegalArgumentException("Unknown argument: " + arg);
			}
		}
		return o;
	}

	private static void printUsage() {
		LOG.info("Usage: PermissionDbGenerator [options]\n"
				+ "  --out <file>        database file (default: " + DEFAULT_OUT + ")\n"
				+ "  --cache <dir>       download cache (default: " + DEFAULT_CACHE + ")\n"
				+ "  --changelog <file>  write a Markdown change log if the content changed\n"
				+ "  --min-api <level>   lowest SDK platform to scan (default: " + DEFAULT_MIN_API + ")\n"
				+ "  --aosp-tag <tag>    AOSP release tag (default: latest android-X.Y.Z_rN)\n"
				+ "  --offline           use only the download cache\n"
				+ "  --force             write the database even if the content didn't change");
	}

	PermissionDbGenerator(Options options) throws IOException {
		this.options = options;
		this.http = new Http(options.cache, options.offline);
		this.supplement = Supplement.load();
	}

	void run() throws IOException {
		List<Platform> platforms = loadPlatforms();
		StubsParser stubs = loadSdk(platforms);
		String aospTag = loadAosp();
		applySupplement(stubs);
		loadAxplorer(stubs);
		resolveConstants(stubs);

		List<String> lines = data.canonicalLines();
		String contentHash = Http.sha256(String.join("\n", lines));
		Platform latest = platforms.get(platforms.size() - 1);
		data.putMeta("schema_version", Integer.toString(DbIo.SCHEMA_VERSION));
		data.putMeta("content_hash", contentHash);
		data.putMeta("generated", LocalDate.now(ZoneOffset.UTC).toString());
		data.putMeta("latest_api", latest.getVersion());
		data.putMeta("aosp_tag", aospTag);
		data.putMeta("api_count", Integer.toString(data.getApis().size()));
		data.putMeta("permission_count", Integer.toString(data.getPermissions().size()));

		String oldHash = DbIo.readContentHash(options.out);
		boolean changed = !contentHash.equals(oldHash);
		LOG.info("APIs with permissions: {}, permissions: {}, provider permissions: {}, downloaded: {} KB",
				data.getApis().size(), data.getPermissions().size(), data.getProviders().size(), http.getDownloaded() / 1024);
		if (changed || options.force) {
			PermissionData old = Files.isRegularFile(options.out) ? DbIo.read(options.out) : null;
			DbIo.write(data, options.out);
			LOG.info("Database written: {}", options.out);
			if (options.changelog != null) {
				String log = ChangeLog.build(old, data);
				Files.writeString(options.changelog, log);
				LOG.info("Change log written: {}", options.changelog);
			}
		} else {
			LOG.info("Content unchanged, keeping {}", options.out);
		}
		writeGithubOutput(changed);
	}

	private List<Platform> loadPlatforms() throws IOException {
		byte[] manifest = http.get(SdkRepository.MANIFEST_URL, false);
		List<Platform> all = SdkRepository.parseStablePlatforms(manifest);
		List<Platform> platforms = new ArrayList<>();
		for (Platform p : all) {
			if (p.getApiLevel() >= options.minApi) {
				platforms.add(p);
			}
		}
		if (platforms.isEmpty()) {
			throw new IOException("No stable SDK platforms found in " + SdkRepository.MANIFEST_URL);
		}
		return platforms;
	}

	/**
	 * @return stubs parser for the latest platform with source stubs
	 */
	private StubsParser loadSdk(List<Platform> platforms) throws IOException {
		List<String> scanned = new ArrayList<>();
		for (Platform p : platforms) {
			RemoteZip zip = RemoteZip.http(http, p.getUrl(), p.getSha1());
			byte[] annotations = zip.read("/data/annotations.zip");
			if (annotations == null) {
				LOG.info("{}: no annotations", p.getPath());
				continue;
			}
			AnnotationsParser.apply(data, annotations, p.getApiLevel());
			scanned.add(p.getVersion());
			data.addSource(new PermissionData.Source("sdk-platform-" + p.getVersion(), p.getUrl(), "sha1:" + p.getSha1()));
			LOG.info("{}: annotations loaded", p.getPath());
		}
		data.putMeta("sdk_platforms", String.join(",", scanned));
		List<Platform> reversed = new ArrayList<>(platforms);
		Collections.reverse(reversed);
		for (Platform p : reversed) {
			byte[] stubsJar = RemoteZip.http(http, p.getUrl(), p.getSha1()).read("/android-stubs-src.jar");
			if (stubsJar != null) {
				LOG.info("{}: source stubs loaded", p.getPath());
				data.putMeta("stubs_platform", p.getVersion());
				return StubsParser.parseJar(stubsJar);
			}
		}
		throw new IOException("No SDK platform with android-stubs-src.jar found");
	}

	private String loadAosp() throws IOException {
		Supplement.Aosp aosp = supplement.aosp;
		String tag = options.aospTag;
		if (tag == null) {
			Supplement.RepoFile fw = aosp.frameworkManifest;
			byte[] tags = http.get(aosp.gitiles + fw.repo + "/+refs/tags?format=JSON", false);
			tag = findLatestReleaseTag(new String(tags, StandardCharsets.UTF_8));
		}
		LOG.info("AOSP release tag: {}", tag);
		byte[] framework = gitilesFile(aosp.frameworkManifest, tag);
		ManifestParser.parseFramework(data, framework, "aosp");
		for (Supplement.RepoFile file : aosp.providerManifests) {
			try {
				ManifestParser.parseProviders(data, gitilesFile(file, tag), "aosp");
			} catch (IOException e) {
				LOG.warn("Skipping provider manifest {} at {}: {}", file.repo, tag, e.getMessage());
			}
		}
		data.addSource(new PermissionData.Source("aosp", aosp.gitiles, tag));
		return tag;
	}

	private byte[] gitilesFile(Supplement.RepoFile file, String tag) throws IOException {
		String url = supplement.aosp.gitiles + file.repo + "/+/refs/tags/" + tag + '/' + file.path + "?format=TEXT";
		byte[] base64 = http.get(url, true);
		return Base64.getMimeDecoder().decode(base64);
	}

	static String findLatestReleaseTag(String tagsJson) throws IOException {
		Matcher m = RELEASE_TAG.matcher(tagsJson);
		String best = null;
		long bestVersion = -1;
		while (m.find()) {
			long version = Long.parseLong(m.group(2)) * 1_000_000_000L
					+ Long.parseLong(m.group(3)) * 10_000_000L
					+ Long.parseLong(m.group(4)) * 100_000L
					+ Long.parseLong(m.group(5));
			if (version > bestVersion) {
				bestVersion = version;
				best = m.group(1);
			}
		}
		if (best == null) {
			throw new IOException("No AOSP release tag found");
		}
		return best;
	}

	private void applySupplement(StubsParser stubs) {
		for (Supplement.ProviderPermissionJson p : supplement.providerPermissions) {
			data.addProvider(new ProviderPermission(p.authority, "", "", p.access, p.permission, null, CURATED));
		}
		for (Supplement.BroadcastJson b : supplement.broadcasts) {
			data.getOrAddBroadcast(b.action, CURATED).setPermission(b.permission, CURATED);
		}
		// content URI fields -> whole provider permissions
		for (Map.Entry<String, List<String>> entry : stubs.getUriFields().entrySet()) {
			String cls = entry.getKey();
			String authority = findAuthority(cls);
			if (authority == null) {
				continue;
			}
			List<ProviderPermission> perms = new ArrayList<>();
			for (ProviderPermission p : data.getProviders()) {
				if (p.getAuthority().equals(authority) && p.getPath().isEmpty()) {
					perms.add(p);
				}
			}
			if (perms.isEmpty()) {
				LOG.warn("No provider permissions for authority '{}' (URI fields of {})", authority, cls);
				continue;
			}
			for (String field : entry.getValue()) {
				Api api = data.getOrAddApi(ApiKind.FIELD, cls, field, null);
				for (ProviderPermission p : perms) {
					data.addRequirement(api, p.getPermission(), "any", false, p.getAccess(), PROVIDER_URI, null);
				}
			}
		}
	}

	private @Nullable String findAuthority(String cls) {
		String best = null;
		int bestLen = -1;
		for (Map.Entry<String, String> e : supplement.uriClasses.entrySet()) {
			String prefix = e.getKey();
			if ((cls.equals(prefix) || cls.startsWith(prefix + '$')) && prefix.length() > bestLen) {
				best = e.getValue();
				bestLen = prefix.length();
			}
		}
		return best;
	}

	private void loadAxplorer(StubsParser stubs) throws IOException {
		Supplement.Axplorer ax = supplement.axplorer;
		for (int level : ax.levels) {
			String url = ax.urlTemplate.replace("{level}", Integer.toString(level));
			List<AxplorerParser.Entry> entries = AxplorerParser.parse(http.get(url, true));
			if (ax.noisePermissions != null) {
				entries = AxplorerParser.removeNoise(entries, ax.noisePermissions);
			}
			AxplorerParser.apply(data, entries, level, stubs);
		}
		String first = ax.urlTemplate.replace("{level}", Integer.toString(ax.levels.get(0)));
		Matcher commit = Pattern.compile("/([0-9a-f]{40})/").matcher(first);
		data.addSource(new PermissionData.Source(AxplorerParser.SOURCE, "https://github.com/reddr/axplorer",
				commit.find() ? commit.group(1) : "unknown"));
	}

	/**
	 * String constants are inlined by the compiler, so store their values for string matching
	 */
	private void resolveConstants(StubsParser stubs) {
		for (Api api : data.getApis()) {
			if (api.getKind() == ApiKind.FIELD) {
				api.setConstant(stubs.getConstant(api.getCls(), api.getMember()));
			}
		}
	}

	private static void writeGithubOutput(boolean changed) throws IOException {
		String output = System.getenv("GITHUB_OUTPUT");
		if (output != null && !output.isEmpty()) {
			Files.writeString(Paths.get(output), "changed=" + changed + '\n', StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
	}

	/**
	 * Curated input, see {@code supplement.json}
	 */
	@SuppressWarnings("unused")
	static final class Supplement {
		Aosp aosp;
		Axplorer axplorer;
		List<ProviderPermissionJson> providerPermissions;
		Map<String, String> uriClasses;
		List<BroadcastJson> broadcasts;

		static final class Aosp {
			String gitiles;
			RepoFile frameworkManifest;
			List<RepoFile> providerManifests;
		}

		static final class RepoFile {
			String repo;
			String path;
		}

		static final class Axplorer {
			String urlTemplate;
			List<Integer> levels;
			@Nullable
			Map<String, String> noisePermissions;
		}

		static final class ProviderPermissionJson {
			String authority;
			String access;
			String permission;
		}

		static final class BroadcastJson {
			String action;
			String permission;
		}

		static Supplement load() throws IOException {
			try (InputStream in = Supplement.class.getResourceAsStream("supplement.json")) {
				if (in == null) {
					throw new IOException("supplement.json not found");
				}
				try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
					return new Gson().fromJson(reader, Supplement.class);
				}
			}
		}
	}
}
