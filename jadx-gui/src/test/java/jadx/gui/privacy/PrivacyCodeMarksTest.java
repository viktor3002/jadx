package jadx.gui.privacy;

import java.io.File;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import jadx.api.ICodeInfo;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.gui.privacy.PrivacyCodeMarks.Mark;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyCodeMarksTest {

	@Test
	void marksInDecompiledCode() throws Exception {
		JadxArgs args = new JadxArgs();
		File dir = new File(getClass().getResource("/privacy/smali").toURI());
		for (File file : Objects.requireNonNull(dir.listFiles())) {
			args.getInputFiles().add(file);
		}
		try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
			decompiler.load();
			PrivacyRules rules = PrivacyRules.getDefault();

			JavaClass helper = decompiler.searchJavaClassByOrigFullName("com.test.Helper");
			ICodeInfo code = helper.getCodeInfo();
			List<Mark> marks = PrivacyCodeMarks.compute(helper.getClassNode(), code, rules);
			// all permission APIs are marked, also if not declared in manifest
			assertThat(marks).extracting(m -> code.getCodeStr().substring(m.getStart(), m.getEnd()))
					.containsExactly("getLastKnownLocation", "content://sms/inbox", "sendTextMessage");
			assertThat(marks).extracting(m -> m.getApi().getCapability().getId())
					.containsExactly("LOCATION", "READ_SMS", "SEND_SMS");

			// 'flag' capabilities (reflection) are not permissions, not marked
			JavaClass dynReceiver = decompiler.searchJavaClassByOrigFullName("com.test.DynReceiver");
			assertThat(PrivacyCodeMarks.compute(dynReceiver.getClassNode(), dynReceiver.getCodeInfo(), rules)).isEmpty();

			// framework method called on app subclass
			JavaClass clicker = decompiler.searchJavaClassByOrigFullName("com.test.Clicker");
			ICodeInfo clickerCode = clicker.getCodeInfo();
			List<Mark> clickerMarks = PrivacyCodeMarks.compute(clicker.getClassNode(), clickerCode, rules);
			assertThat(clickerMarks).extracting(m -> clickerCode.getCodeStr().substring(m.getStart(), m.getEnd()))
					.containsExactly("dispatchGesture");
		}
	}
}
