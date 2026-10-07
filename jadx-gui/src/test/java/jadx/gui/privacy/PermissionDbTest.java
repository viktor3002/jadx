package jadx.gui.privacy;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jadx.api.ICodeInfo;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.core.dex.nodes.MethodNode;
import jadx.gui.privacy.PermissionDb.DbApi;
import jadx.gui.privacy.PermissionDb.Requirement;
import jadx.gui.privacy.PermissionUsages.ApiUsage;
import jadx.gui.privacy.PermissionUsages.CallSite;
import jadx.gui.privacy.PrivacyCodeMarks.Mark;
import jadx.gui.privacy.PrivacyRules.Api;
import jadx.gui.privacy.PrivacyRules.Capability;
import jadx.gui.utils.LangLocale;
import jadx.gui.utils.NLS;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionDbTest {

	private static final String FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";
	private static final String NETWORK_STATE = "android.permission.ACCESS_NETWORK_STATE";

	private static final String MANIFEST = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
			+ "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.test\">\n"
			+ "  <uses-permission android:name=\"" + FINE_LOCATION + "\"/>\n"
			+ "  <uses-permission android:name=\"" + NETWORK_STATE + "\"/>\n"
			+ "  <uses-permission android:name=\"android.permission.CAMERA\"/>\n"
			+ "  <uses-permission android:name=\"android.permission.RECEIVE_SMS\"/>\n"
			+ "  <application>\n"
			+ "    <service android:name=\".NetService\"/>\n"
			+ "    <service android:name=\".SpyService\"/>\n"
			+ "    <receiver android:name=\".SmsReceiver\">\n"
			+ "      <intent-filter><action android:name=\"android.provider.Telephony.SMS_RECEIVED\"/></intent-filter>\n"
			+ "    </receiver>\n"
			+ "  </application>\n"
			+ "</manifest>\n";

	private static PermissionDb db;
	private static PrivacyRules rules;

	@BeforeAll
	static void load() throws Exception {
		NLS.setLocale(new LangLocale("en", "US"));
		db = PermissionDb.loadBundled();
		rules = PrivacyRules.load(PrivacyRules.loadRulesJson(), db);
	}

	@Test
	void bundledDatabase() {
		assertThat(db.getMeta("schema_version")).isEqualTo(Integer.toString(PermissionDb.SCHEMA_VERSION));
		assertThat(db.getContentHash()).hasSize(64);
		assertThat(db.getApis()).hasSizeGreaterThan(1000);

		assertThat(db.getPermission("android.permission.READ_SMS").getProtectionLevel()).contains("dangerous");
		assertThat(db.getPermission("android.permission.WRITE_SECURE_SETTINGS").getProtectionLevel()).contains("signature");

		DbApi lastKnown = findApi("android.location.LocationManager", "getLastKnownLocation");
		assertThat(lastKnown.getParams()).isEqualTo("java.lang.String");
		assertThat(lastKnown.getRequirements()).extracting(Requirement::getPermission).contains(FINE_LOCATION);

		assertThat(db.getProviders().get("call_log")).extracting(Requirement::getPermission)
				.contains("android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG");
		assertThat(db.getBroadcastPermissions()).containsEntry("android.provider.Telephony.SMS_RECEIVED", "android.permission.RECEIVE_SMS");
	}

	@Test
	void mergedRules() {
		// database API with a permission of a rule capability counts in the score
		Api scan = rules.getApi("m:android.net.wifi.WifiManager.getScanResults");
		assertThat(scan).isNotNull();
		assertThat(scan.getCapability().getId()).isEqualTo("LOCATION");
		assertThat(scan.getOverloads()).containsExactly("");

		// other permissions: own capability with score 0, not shown in the overview
		Api netInfo = rules.getApi("m:android.net.ConnectivityManager.getActiveNetworkInfo");
		Capability netCap = netInfo.getCapability();
		assertThat(netCap.isPrivacy()).isFalse();
		assertThat(netCap.getScore()).isZero();
		assertThat(netCap.getPermissions()).containsExactly(NETWORK_STATE);
		assertThat(netCap.getLabel()).isEqualTo("ACCESS_NETWORK_STATE");

		// rule table entry wins over database entry with the same key
		assertThat(rules.getApi("s:content://call_log").getCapability().getId()).isEqualTo("CALL_LOG");
		assertThat(rules.getApi("m:android.location.LocationManager.getLastKnownLocation").getOverloads()).isNull();

		// content URI fields from the database
		Api voicemail = rules.getApi("f:android.provider.VoicemailContract$Voicemails.CONTENT_URI");
		assertThat(voicemail).isNotNull();
		assertThat(voicemail.getPermissions()).contains("com.android.voicemail.permission.READ_VOICEMAIL");

		// saved reports are recomputed when the database changes
		assertThat(rules.getFingerprint()).endsWith(db.getContentHash());
		assertThat(PrivacyRules.load(PrivacyRules.loadRulesJson()).getFingerprint()).isNotEqualTo(rules.getFingerprint());
	}

	@Test
	void codeMarksAndUsages() throws Exception {
		JadxArgs args = new JadxArgs();
		File dir = new File(getClass().getResource("/privacy/smali").toURI());
		for (File file : Objects.requireNonNull(dir.listFiles())) {
			args.getInputFiles().add(file);
		}
		try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
			decompiler.load();
			ManifestInfo manifest = ManifestInfo.parse(MANIFEST, args.getSecurity());

			JavaClass scanner = decompiler.searchJavaClassByOrigFullName("com.test.Scanner");
			ICodeInfo code = scanner.getCodeInfo();
			List<Mark> marks = PrivacyCodeMarks.compute(scanner.getClassNode(), code, rules);
			// the call of an overload without permission ('getActiveNetworkInfo(int)') is not marked
			assertThat(marks).extracting(m -> code.getCodeStr().substring(m.getStart(), m.getEnd()))
					.containsExactly("getScanResults", "getActiveNetworkInfo", "android.bluetooth.adapter.action.DISCOVERY_FINISHED");
			assertThat(marks).extracting(Mark::isPrivacy).containsExactly(true, false, false);

			// hover annotation
			PermissionAnnotation scanAnnotation = PermissionAnnotation.build(marks.get(0).getHit(), manifest, db);
			assertThat(scanAnnotation.getAnnotation())
					.isEqualTo("@RequiresPermission(allOf = {ACCESS_FINE_LOCATION, ACCESS_WIFI_STATE})");
			assertThat(scanAnnotation.getDetails()).contains("Location", "+1", "dangerous", "API 33–37", "Android SDK");
			PermissionAnnotation netAnnotation = PermissionAnnotation.build(marks.get(1).getHit(), manifest, db);
			assertThat(netAnnotation.getAnnotation()).isEqualTo("@RequiresPermission(ACCESS_NETWORK_STATE)");
			assertThat(netAnnotation.getDetails()).contains("normal").doesNotContain("not declared");
			PermissionAnnotation btAnnotation = PermissionAnnotation.build(marks.get(2).getHit(), manifest, db);
			assertThat(btAnnotation.getAnnotation()).isEqualTo("@RequiresPermission(BLUETOOTH_SCAN)");
			assertThat(btAnnotation.getDetails()).contains("not declared");

			// overview score: database API counts for the location capability
			PrivacyCallGraph graph = PrivacyCallGraph.build(decompiler.getRoot(), rules, manifest, () -> false);
			PrivacyReport report = PrivacyAnalyzer.buildReport(graph, manifest, "hash");
			PrivacyReport.Row net = report.getComponents().stream()
					.filter(r -> r.getCls().equals("com.test.NetService")).findFirst().orElseThrow();
			assertThat(net.getHits()).containsExactly("m:android.net.wifi.WifiManager.getScanResults");
			assertThat(rules.score(net.getHits())).isEqualTo(1);

			// permission usages with call paths from the services
			PermissionUsages fine = PermissionUsages.compute(graph, manifest, FINE_LOCATION, 10);
			assertThat(fine.isDeclared()).isTrue();
			Map<String, ApiUsage> byLabel = new TreeMap<>();
			fine.getApis().forEach(a -> byLabel.put(a.getApi().getLabel(), a));
			assertThat(byLabel).containsKeys("WifiManager.getScanResults()", "LocationManager.getLastKnownLocation()");
			CallSite scanSite = byLabel.get("WifiManager.getScanResults()").getCallSites().get(0);
			assertThat(scanSite.getMethod().getMethodInfo().getName()).isEqualTo("scan");
			assertThat(scanSite.getEntries()).hasSize(1);
			assertThat(scanSite.getEntries().get(0).getComponent().getCls().getRawName()).isEqualTo("com.test.NetService");
			assertThat(scanSite.getEntries().get(0).getPath()).extracting(MethodNode::getName)
					.containsExactly("onStartCommand", "scan");
			CallSite locationSite = byLabel.get("LocationManager.getLastKnownLocation()").getCallSites().get(0);
			assertThat(locationSite.getEntries().get(0).getComponent().getCls().getRawName()).isEqualTo("com.test.SpyService");

			PermissionUsages sms = PermissionUsages.compute(graph, manifest, "android.permission.RECEIVE_SMS", 10);
			assertThat(sms.getManifestUsages()).anyMatch(s -> s.contains("SMS_RECEIVED"));

			// declared, but not used by the code
			PermissionUsages camera = PermissionUsages.compute(graph, manifest, "android.permission.CAMERA", 10);
			assertThat(camera.getApis()).isEmpty();
			assertThat(camera.getManifestUsages()).isEmpty();

			Map<String, Integer> counts = PermissionUsages.countApisByPermission(graph);
			assertThat(counts).containsEntry(NETWORK_STATE, 1).doesNotContainKey("android.permission.CAMERA");
		}
	}

	private static DbApi findApi(String cls, String member) {
		return db.getApis().stream()
				.filter(a -> a.getCls().equals(cls) && a.getMember().equals(member))
				.findFirst().orElseThrow();
	}
}
