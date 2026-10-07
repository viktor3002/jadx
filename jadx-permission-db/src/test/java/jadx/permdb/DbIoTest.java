package jadx.permdb;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.permdb.PermissionData.ApiKind;
import jadx.permdb.PermissionData.PermissionInfo;
import jadx.permdb.PermissionData.ProviderPermission;

import static org.assertj.core.api.Assertions.assertThat;

class DbIoTest {

	@TempDir
	Path tmp;

	@Test
	void writeAndRead() throws Exception {
		PermissionData data = sample();
		Path db = tmp.resolve("test.db");
		DbIo.write(data, db);
		assertThat(DbIo.readContentHash(db)).isEqualTo("hash1");

		PermissionData restored = DbIo.read(db);
		assertThat(restored.canonicalLines()).isEqualTo(data.canonicalLines());
		assertThat(restored.getMeta()).isEqualTo(data.getMeta());
		assertThat(restored.getSources()).hasSize(1);
	}

	@Test
	void changeLog() {
		PermissionData old = sample();
		PermissionData current = sample();
		PermissionData.Api api = current.getOrAddApi(ApiKind.METHOD, "android.net.wifi.WifiManager", "getScanResults", "");
		current.addRequirement(api, "android.permission.ACCESS_FINE_LOCATION", "all", false, null, "sdk-annotations", 33);
		current.putMeta("sdk_platforms", "33");

		String log = ChangeLog.build(old, current);
		assertThat(log).contains("| api | 1 | 0 |");
		assertThat(log).contains("method android.net.wifi.WifiManager.getScanResults() ");
		assertThat(log).doesNotContain("<summary>Removed");
		assertThat(ChangeLog.build(null, current)).contains("| permission | 1 | 0 |");
	}

	@Test
	void remoteZip() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("android-37/data/annotations.zip"));
			zip.write("compressed content content content".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			ZipEntry stored = new ZipEntry("android-37/source.properties");
			stored.setMethod(ZipEntry.STORED);
			byte[] props = "Pkg.Revision=1".getBytes(StandardCharsets.UTF_8);
			stored.setSize(props.length);
			CRC32 crc = new CRC32();
			crc.update(props);
			stored.setCrc(crc.getValue());
			zip.putNextEntry(stored);
			zip.write(props);
			zip.closeEntry();
		}
		RemoteZip remoteZip = RemoteZip.bytes(out.toByteArray());
		assertThat(new String(remoteZip.read("/data/annotations.zip"), StandardCharsets.UTF_8))
				.isEqualTo("compressed content content content");
		assertThat(new String(remoteZip.read("/source.properties"), StandardCharsets.UTF_8)).isEqualTo("Pkg.Revision=1");
		assertThat(remoteZip.read("/android-stubs-src.jar")).isNull();
	}

	private static PermissionData sample() {
		PermissionData data = new PermissionData();
		data.addPermission(new PermissionInfo("android.permission.READ_SMS", "dangerous", null, null, "aosp"));
		PermissionData.Api api = data.getOrAddApi(ApiKind.METHOD, "android.location.LocationManager", "getLastKnownLocation",
				"java.lang.String");
		data.addRequirement(api, "android.permission.ACCESS_FINE_LOCATION", "any", false, null, "sdk-annotations", 26);
		data.addRequirement(api, "android.permission.ACCESS_FINE_LOCATION", "any", false, null, "sdk-annotations", 37);
		PermissionData.Api field = data.getOrAddApi(ApiKind.FIELD, "android.provider.CallLog$Calls", "CONTENT_URI", null);
		data.addRequirement(field, "android.permission.READ_CALL_LOG", "any", false, "read", "provider-uri", null);
		PermissionData.Api constant = data.getOrAddApi(ApiKind.FIELD, "android.nfc.NfcAdapter", "ACTION_TRANSACTION_DETECTED", null);
		constant.setConstant("android.nfc.action.TRANSACTION_DETECTED");
		data.addRequirement(constant, "android.permission.NFC_TRANSACTION_EVENT", "all", true, null, "sdk-annotations", 28);
		data.addProvider(new ProviderPermission("call_log", "", "", "read", "android.permission.READ_CALL_LOG",
				"com.android.providers.contacts", "aosp"));
		data.getOrAddBroadcast("android.provider.Telephony.SMS_RECEIVED", "curated")
				.setPermission("android.permission.RECEIVE_SMS", "curated");
		data.addSource(new PermissionData.Source("aosp", "https://android.googlesource.com/", "android-17.0.0_r1"));
		data.putMeta("schema_version", "1");
		data.putMeta("content_hash", "hash1");
		return data;
	}
}
