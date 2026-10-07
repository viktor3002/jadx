package jadx.gui.privacy;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.gui.privacy.PrivacyReport.Row;
import jadx.gui.privacy.PrivacyReport.RowKind;
import jadx.gui.privacy.PrivacyRules.Api;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyAnalyzerTest {

	private static final String MANIFEST = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
			+ "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.test\">\n"
			+ "  <uses-permission android:name=\"android.permission.ACCESS_FINE_LOCATION\"/>\n"
			+ "  <uses-permission android:name=\"android.permission.RECEIVE_SMS\"/>\n"
			+ "  <application>\n"
			+ "    <service android:name=\".SpyService\"/>\n"
			+ "    <service android:name=\"com.test.Clicker\" android:permission=\"android.permission.BIND_ACCESSIBILITY_SERVICE\">\n"
			+ "      <intent-filter><action android:name=\"android.accessibilityservice.AccessibilityService\"/></intent-filter>\n"
			+ "    </service>\n"
			+ "    <service android:name=\"Ghost\"/>\n"
			+ "    <receiver android:name=\".SmsReceiver\">\n"
			+ "      <intent-filter><action android:name=\"android.provider.Telephony.SMS_RECEIVED\"/></intent-filter>\n"
			+ "    </receiver>\n"
			+ "  </application>\n"
			+ "</manifest>\n";

	@Test
	void defaultRulesAreValid() {
		PrivacyRules rules = PrivacyRules.getDefault();
		assertThat(rules.getApis()).isNotEmpty();
		assertThat(rules.getFingerprint()).isNotEqualTo("custom");
		for (Api api : rules.getApis()) {
			assertThat(rules.getApi(api.getKey())).as("duplicate API rule: %s", api.getKey()).isSameAs(api);
		}
		assertThat(rules.getCapability("SCREEN_CAPTURE").getScore()).isEqualTo(2);
		assertThat(rules.getCapability("LOCATION").getScore()).isEqualTo(1);
		assertThat(rules.getCapability("REFLECTION").getFlag()).isEqualTo("REFL");
		// abbreviations for crowded rows in the overview
		for (PrivacyRules.Capability cap : rules.getCapabilities().values()) {
			if (cap.isPrivacy()) {
				assertThat(cap.getShortLabel()).as("short label of %s", cap.getId()).hasSizeLessThanOrEqualTo(8).isUpperCase();
			}
		}
		assertThat(rules.getCapability("LOCATION").getShortLabel()).isEqualTo("LOC");
	}

	@Test
	void parseManifest() {
		ManifestInfo manifest = ManifestInfo.parse(MANIFEST, new JadxArgs().getSecurity());
		assertThat(manifest.getPackage()).isEqualTo("com.test");
		assertThat(manifest.getUsesPermissions())
				.containsExactly("android.permission.ACCESS_FINE_LOCATION", "android.permission.RECEIVE_SMS");
		assertThat(manifest.getComponents()).extracting(ManifestInfo.Component::getClsName)
				.containsExactly("com.test.SpyService", "com.test.Clicker", "com.test.Ghost", "com.test.SmsReceiver");
		assertThat(manifest.getComponentPermissions()).containsExactly("android.permission.BIND_ACCESSIBILITY_SERVICE");
	}

	@Test
	void analyzeSample() throws Exception {
		JadxArgs args = new JadxArgs();
		File dir = new File(getClass().getResource("/privacy/smali").toURI());
		for (File file : Objects.requireNonNull(dir.listFiles())) {
			args.getInputFiles().add(file);
		}
		try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
			decompiler.load();
			RootNode root = decompiler.getRoot();
			PrivacyRules rules = PrivacyRules.getDefault();
			ManifestInfo manifest = ManifestInfo.parse(MANIFEST, args.getSecurity());
			PrivacyCallGraph graph = PrivacyCallGraph.build(root, rules, manifest, () -> false);
			PrivacyReport report = PrivacyAnalyzer.buildReport(graph, manifest, "hash");

			Map<String, Row> components = byCls(report.getComponents());
			assertThat(components).containsOnlyKeys(
					"com.test.SpyService", "com.test.Clicker", "com.test.Ghost", "com.test.SmsReceiver", "com.test.DynReceiver");

			// location via helper, screen capture via Runnable passed to Thread, reflection via dynamic
			// receiver,
			// SEND_SMS not declared in manifest -> ignored as dead code
			Row spy = components.get("com.test.SpyService");
			assertThat(spy.getKind()).isEqualTo(RowKind.SERVICE);
			assertThat(spy.getHits()).containsExactlyInAnyOrder(
					"m:android.location.LocationManager.getLastKnownLocation",
					"m:android.media.projection.MediaProjectionManager.createScreenCaptureIntent",
					"m:java.lang.reflect.Method.invoke");
			assertThat(rules.score(spy.getHits())).isEqualTo(3);

			// framework method called on app subclass + manifest permission (intent action for same capability
			// skipped)
			Row clicker = components.get("com.test.Clicker");
			assertThat(clicker.getHits()).containsExactlyInAnyOrder(
					"m:android.accessibilityservice.AccessibilityService.dispatchGesture",
					PrivacyRules.manifestPermissionKey("android.permission.BIND_ACCESSIBILITY_SERVICE"));
			assertThat(rules.score(clicker.getHits())).isEqualTo(4);

			Row sms = components.get("com.test.SmsReceiver");
			assertThat(sms.getKind()).isEqualTo(RowKind.RECEIVER);
			assertThat(rules.score(sms.getHits())).isEqualTo(4);

			Row ghost = components.get("com.test.Ghost");
			assertThat(ghost.isMissing()).isTrue();
			assertThat(ghost.getHits()).isEmpty();

			Row dyn = components.get("com.test.DynReceiver");
			assertThat(dyn.getKind()).isEqualTo(RowKind.DYNAMIC_RECEIVER);
			assertThat(dyn.getHits()).containsExactly("m:java.lang.reflect.Method.invoke");
			assertThat(rules.score(dyn.getHits())).isZero();

			// class view: recursive and direct hits
			Map<String, Row> classes = byCls(report.getClasses());
			assertThat(classes).doesNotContainKey("com.test.Util");
			assertThat(classes.get("com.test.Helper").getDirectHits())
					.containsExactly("m:android.location.LocationManager.getLastKnownLocation");
			assertThat(classes.get("com.test.SpyService").getDirectHits()).isEmpty();
			assertThat(classes.get("com.test.SpyService").getHits()).hasSize(3);

			// call path evidence
			ClassNode spyCls = root.resolveRawClass("com.test.SpyService");
			Api locationApi = rules.getApi("m:android.location.LocationManager.getLastKnownLocation");
			List<List<MethodNode>> paths = graph.findCallPaths(PrivacyAnalyzer.componentRoots(graph, spyCls), locationApi.getIndex(), 10);
			assertThat(paths).hasSize(1);
			assertThat(paths.get(0)).extracting(m -> m.getMethodInfo().getName())
					.containsExactly("onStartCommand", "collect");

			// saved in project file as json
			Gson gson = new Gson();
			PrivacyReport restored = gson.fromJson(gson.toJson(report), PrivacyReport.class);
			assertThat(restored.isValidFor("hash", rules)).isTrue();
			assertThat(restored.isValidFor("other", rules)).isFalse();
			assertThat(byCls(restored.getComponents()).get("com.test.SpyService").getHits()).isEqualTo(spy.getHits());
		}
	}

	private static Map<String, Row> byCls(List<Row> rows) {
		return rows.stream().collect(Collectors.toMap(Row::getCls, Function.identity()));
	}
}
