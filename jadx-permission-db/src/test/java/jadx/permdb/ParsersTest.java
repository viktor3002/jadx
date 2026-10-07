package jadx.permdb;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jadx.permdb.PermissionData.ApiKind;
import jadx.permdb.PermissionData.ProviderPermission;
import jadx.permdb.SdkRepository.Platform;

import static org.assertj.core.api.Assertions.assertThat;

class ParsersTest {

	@Test
	void javaNames() {
		assertThat(JavaNames.toBinaryName("android.app.Notification.Builder")).isEqualTo("android.app.Notification$Builder");
		assertThat(JavaNames.toBinaryName("android.provider.ContactsContract.CommonDataKinds.Phone"))
				.isEqualTo("android.provider.ContactsContract$CommonDataKinds$Phone");
		assertThat(JavaNames.eraseParams("java.util.List<java.lang.String>, int[], java.lang.Object..., T"))
				.isEqualTo("java.util.List,int[],java.lang.Object[],T");
		assertThat(JavaNames.eraseParams("java.util.Map<java.lang.String, java.util.List<android.net.Uri>>, android.os.Bundle"))
				.isEqualTo("java.util.Map,android.os.Bundle");
		assertThat(JavaNames.eraseParams("")).isEmpty();
	}

	@Test
	void annotations() throws Exception {
		String xml = "<root>\n"
				// unescaped generics (invalid XML) appear in some SDK versions
				+ "<item name=\"android.location.LocationManager void requestFlush(java.lang.String, java.util.List<java.lang.String>, int)\">\n"
				+ "  <annotation name=\"androidx.annotation.RequiresPermission\">\n"
				+ "    <val name=\"anyOf\" val=\"{&quot;android.permission.ACCESS_COARSE_LOCATION&quot;, &quot;android.permission.ACCESS_FINE_LOCATION&quot;}\" />\n"
				+ "  </annotation>\n"
				+ "</item>\n"
				+ "<item name=\"android.media.AudioRecord AudioRecord(int, int, int, int, int)\">\n"
				+ "  <annotation name=\"androidx.annotation.RequiresPermission\">\n"
				+ "    <val name=\"value\" val=\"&quot;android.permission.RECORD_AUDIO&quot;\" />\n"
				+ "  </annotation>\n"
				+ "</item>\n"
				+ "<item name=\"android.telephony.TelephonyManager void listen(android.telephony.PhoneStateListener, int)\">\n"
				+ "  <annotation name=\"androidx.annotation.RequiresPermission\">\n"
				+ "    <val name=\"value\" val=\"&quot;android.permission.READ_PHONE_STATE&quot;\" />\n"
				+ "    <val name=\"conditional\" val=\"true\" />\n"
				+ "  </annotation>\n"
				+ "</item>\n"
				+ "<item name=\"android.nfc.NfcAdapter ACTION_TRANSACTION_DETECTED\">\n"
				+ "  <annotation name=\"androidx.annotation.RequiresPermission\">\n"
				+ "    <val name=\"value\" val=\"&quot;android.permission.NFC_TRANSACTION_EVENT&quot;\" />\n"
				+ "  </annotation>\n"
				+ "</item>\n"
				+ "<item name=\"android.content.ContentResolver android.net.Uri insert(android.net.Uri, android.content.ContentValues) 0\">\n"
				+ "  <annotation name=\"androidx.annotation.RequiresPermission.Write\" />\n"
				+ "</item>\n"
				+ "<item name=\"android.app.Activity void setTitle(int)\">\n"
				+ "  <annotation name=\"androidx.annotation.StringRes\" />\n"
				+ "</item>\n"
				+ "</root>\n";
		List<AnnotationsParser.Item> items = AnnotationsParser.parseXml(xml.getBytes(StandardCharsets.UTF_8));
		assertThat(items).hasSize(4);

		AnnotationsParser.Item flush = items.get(0);
		assertThat(flush.kind).isEqualTo(ApiKind.METHOD);
		assertThat(flush.cls).isEqualTo("android.location.LocationManager");
		assertThat(flush.member).isEqualTo("requestFlush");
		assertThat(flush.params).isEqualTo("java.lang.String,java.util.List,int");
		assertThat(flush.mode).isEqualTo("any");
		assertThat(flush.permissions).containsExactly(
				"android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION");

		AnnotationsParser.Item ctor = items.get(1);
		assertThat(ctor.member).isEqualTo("<init>");
		assertThat(ctor.params).isEqualTo("int,int,int,int,int");
		assertThat(ctor.mode).isEqualTo("all");

		assertThat(items.get(2).conditional).isTrue();

		AnnotationsParser.Item field = items.get(3);
		assertThat(field.kind).isEqualTo(ApiKind.FIELD);
		assertThat(field.member).isEqualTo("ACTION_TRANSACTION_DETECTED");
		assertThat(field.params).isNull();
	}

	@Test
	void stubs() {
		String src = "package android.provider;\n"
				+ "@SuppressWarnings({\"unchecked\", \"deprecation\", \"all\"})\n"
				+ "public final class Telephony {\n"
				+ "Telephony() { throw new RuntimeException(\"Stub!\"); }\n"
				+ "public static final class Sms implements android.provider.BaseColumns {\n"
				+ "Sms() { throw new RuntimeException(\"Stub!\"); }\n"
				+ "public static final android.net.Uri CONTENT_URI;\n"
				+ "static { CONTENT_URI = null; }\n"
				+ "public static final class Intents {\n"
				+ "@android.annotation.SdkConstant(android.annotation.SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)"
				+ " public static final java.lang.String SMS_RECEIVED_ACTION = \"android.provider.Telephony.SMS_RECEIVED\";\n"
				+ "public static final java.lang.String EXTRA = \"a \\\"quoted\\\" {brace}\";\n"
				+ "}\n"
				+ "}\n"
				+ "public static final java.lang.String AUTHORITY = \"telephony\";\n"
				+ "}\n";
		String activity = "package android.app;\n"
				+ "public class Activity extends android.view.ContextThemeWrapper implements android.view.Window.Callback {\n"
				+ "}\n";
		StubsParser stubs = new StubsParser();
		stubs.parseSource(src);
		stubs.parseSource(activity);
		assertThat(stubs.getConstant("android.provider.Telephony$Sms$Intents", "SMS_RECEIVED_ACTION"))
				.isEqualTo("android.provider.Telephony.SMS_RECEIVED");
		assertThat(stubs.getConstant("android.provider.Telephony$Sms$Intents", "EXTRA")).isEqualTo("a \"quoted\" {brace}");
		assertThat(stubs.getConstant("android.provider.Telephony", "AUTHORITY")).isEqualTo("telephony");
		assertThat(stubs.getUriFields()).containsEntry("android.provider.Telephony$Sms", Collections.singletonList("CONTENT_URI"));
		assertThat(stubs.getBroadcastActions()).containsExactly("android.provider.Telephony.SMS_RECEIVED");
		assertThat(stubs.getSuperClass("android.app.Activity")).isEqualTo("android.view.ContextThemeWrapper");
	}

	@Test
	void axplorer() {
		String content = "android.app.KeyguardManager$KeyguardLock.disableKeyguard()void  ::  android.permission.DISABLE_KEYGUARD\n"
				+ "android.content.Context.setWallpaper(android.graphics.Bitmap)void  ::  android.permission.SET_WALLPAPER\n"
				+ "android.app.Activity.setWallpaper(android.graphics.Bitmap)void  ::  android.permission.SET_WALLPAPER\n"
				+ "android.app.Service.stopSelf()void  ::  android.permission.BROADCAST_STICKY\n"
				+ "android.content.Context.sendStickyBroadcast(android.content.Intent)void  ::  android.permission.BROADCAST_STICKY\n"
				+ "android.bluetooth.BluetoothAdapter.cancelDiscovery()boolean  ::  android.permission.BLUETOOTH, android.permission.BLUETOOTH_ADMIN\n";
		List<AxplorerParser.Entry> entries = AxplorerParser.parse(content.getBytes(StandardCharsets.UTF_8));
		assertThat(entries).hasSize(6);
		assertThat(entries.get(0).cls).isEqualTo("android.app.KeyguardManager$KeyguardLock");
		assertThat(entries.get(1).params).isEqualTo("android.graphics.Bitmap");
		assertThat(entries.get(5).permissions).containsExactly("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN");

		List<AxplorerParser.Entry> filtered = AxplorerParser.removeNoise(entries,
				Collections.singletonMap("android.permission.BROADCAST_STICKY", "StickyBroadcast"));
		assertThat(filtered).extracting(e -> e.method).doesNotContain("stopSelf").contains("sendStickyBroadcast");

		StubsParser stubs = new StubsParser();
		stubs.parseSource("package android.app;\npublic class Activity extends android.view.ContextThemeWrapper {\n}\n");
		stubs.parseSource("package android.view;\npublic class ContextThemeWrapper extends android.content.ContextWrapper {\n}\n");
		stubs.parseSource("package android.content;\npublic class ContextWrapper extends android.content.Context {\n}\n");
		PermissionData data = new PermissionData();
		AxplorerParser.apply(data, filtered, 25, stubs);
		// inherited entry with the same permission is skipped, super class entry is kept
		assertThat(data.getApi(ApiKind.METHOD, "android.app.Activity", "setWallpaper", "android.graphics.Bitmap")).isNull();
		PermissionData.Api wallpaper = data.getApi(ApiKind.METHOD, "android.content.Context", "setWallpaper", "android.graphics.Bitmap");
		assertThat(wallpaper.getRequirements()).hasSize(1);
		assertThat(wallpaper.getRequirements().iterator().next().getMinApi()).isEqualTo(25);
	}

	@Test
	void manifests() throws Exception {
		String framework = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"android\">\n"
				+ "  <protected-broadcast android:name=\"android.intent.action.BOOT_COMPLETED\" />\n"
				+ "  <permission android:name=\"android.permission.READ_SMS\" android:permissionGroup=\"android.permission-group.UNDEFINED\"\n"
				+ "      android:permissionFlags=\"hardRestricted\" android:protectionLevel=\"dangerous\" />\n"
				+ "</manifest>\n";
		String providers =
				"<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.android.providers.contacts\">\n"
						+ "  <application>\n"
						+ "    <provider android:name=\"CallLogProvider\" android:authorities=\"call_log\" android:exported=\"true\"\n"
						+ "        android:readPermission=\"android.permission.READ_CALL_LOG\" android:writePermission=\"android.permission.WRITE_CALL_LOG\">\n"
						+ "      <path-permission android:pathPrefix=\"/call_composer\" android:permission=\"android.permission.MANAGE_OWN_CALLS\" />\n"
						+ "    </provider>\n"
						+ "    <provider android:name=\"Internal\" android:authorities=\"internal;internal2\" android:exported=\"false\"\n"
						+ "        android:permission=\"android.permission.INTERNAL\" />\n"
						+ "    <provider android:name=\"Both\" android:authorities=\"a;b\" android:permission=\"android.permission.BOTH\" />\n"
						+ "  </application>\n"
						+ "</manifest>\n";
		PermissionData data = new PermissionData();
		ManifestParser.parseFramework(data, framework.getBytes(StandardCharsets.UTF_8), "aosp");
		ManifestParser.parseProviders(data, providers.getBytes(StandardCharsets.UTF_8), "aosp");

		assertThat(data.getPermissions()).hasSize(1);
		PermissionData.PermissionInfo readSms = data.getPermissions().iterator().next();
		assertThat(readSms.getProtectionLevel()).isEqualTo("dangerous");
		assertThat(readSms.getFlags()).isEqualTo("hardRestricted");
		assertThat(data.getBroadcasts()).extracting(PermissionData.Broadcast::isProtectedBroadcast).containsExactly(true);

		assertThat(data.getProviders()).extracting(ProviderPermission::getKey).containsExactly(
				"a  read android.permission.BOTH",
				"a  write android.permission.BOTH",
				"b  read android.permission.BOTH",
				"b  write android.permission.BOTH",
				"call_log  read android.permission.READ_CALL_LOG",
				"call_log  write android.permission.WRITE_CALL_LOG",
				"call_log /call_composer read android.permission.MANAGE_OWN_CALLS",
				"call_log /call_composer write android.permission.MANAGE_OWN_CALLS");
	}

	@Test
	void sdkRepository() throws Exception {
		String xml = "<sdk:sdk-repository xmlns:sdk=\"http://schemas.android.com/sdk/android/repo/repository2/03\""
				+ " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"
				+ platform("platforms;android-36.1", "36.1", "true", "", "channel-0", "platform-36.1_r01.zip", "")
				+ platform("platforms;android-36", "36", "true", "", "channel-0", "platform-36_r02.zip", "")
				+ platform("platforms;android-36-ext19", "36x", "false", "", "channel-0", "platform-36-ext19_r01.zip", "")
				+ platform("platforms;android-37.2-beta1", "37.2", "true", "", "channel-1", "beta.zip", "")
				+ platform("platforms;android-CANARY", "37.2", "true", "CANARY", "channel-0", "canary.zip", "")
				+ platform("platforms;android-34", "34", "true", "", "channel-0", "platform-34-ext7_r02.zip", " obsolete=\"true\"")
				+ "</sdk:sdk-repository>\n";
		List<Platform> platforms = SdkRepository.parseStablePlatforms(xml.getBytes(StandardCharsets.UTF_8));
		assertThat(platforms).extracting(Platform::getVersion).containsExactly("36", "36.1");
		assertThat(platforms.get(0).getUrl()).isEqualTo(SdkRepository.BASE_URL + "platform-36_r02.zip");
		assertThat(platforms.get(1).getApiLevel()).isEqualTo(36);
		assertThat(platforms.get(1).getMinor()).isEqualTo(1);
	}

	private static String platform(String path, String api, String baseExt, String codename, String channel, String url, String extra) {
		return "<remotePackage path=\"" + path + "\"" + extra + ">\n"
				+ "<type-details xsi:type=\"sdk:platformDetailsType\"><api-level>" + api + "</api-level>"
				+ "<codename>" + codename + "</codename><base-extension>" + baseExt + "</base-extension></type-details>\n"
				+ "<channelRef ref=\"" + channel + "\"/>\n"
				+ "<archives><archive><complete><size>1</size><checksum type=\"sha1\">abc</checksum><url>" + url
				+ "</url></complete></archive></archives>\n"
				+ "</remotePackage>\n";
	}

	@Test
	void latestReleaseTag() throws Exception {
		String json = ")]}'\n{\"android-16.0.0_r4\": {}, \"android-17.0.0_r1\": {}, \"android-16.0.0_r10\": {},"
				+ " \"android-security-17.0.0_r9\": {}, \"android-cts-17.0_r5\": {}}";
		assertThat(PermissionDbGenerator.findLatestReleaseTag(json)).isEqualTo("android-17.0.0_r1");
	}

	@Test
	void requirementLevels() {
		PermissionData data = new PermissionData();
		PermissionData.Api api = data.getOrAddApi(ApiKind.METHOD, "a.B", "c", "");
		for (int level : Arrays.asList(30, 26, 37)) {
			data.addRequirement(api, "p", "all", false, null, "s", level);
		}
		PermissionData.Requirement req = api.getRequirements().iterator().next();
		assertThat(req.getMinApi()).isEqualTo(26);
		assertThat(req.getMaxApi()).isEqualTo(37);
		Map<String, String> meta = data.getMeta();
		assertThat(meta).isEmpty();
	}
}
