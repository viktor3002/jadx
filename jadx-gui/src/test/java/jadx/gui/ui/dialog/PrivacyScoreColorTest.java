package jadx.gui.ui.dialog;

import java.awt.Color;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyScoreColorTest {

	@Test
	void scoreColorScale() {
		assertThat(PrivacyOverviewDialog.ScoreRenderer.scoreColor(0)).isEqualTo(Color.WHITE);
		assertThat(PrivacyOverviewDialog.ScoreRenderer.scoreColor(1)).isEqualTo(new Color(139, 0, 0));
		Color half = PrivacyOverviewDialog.ScoreRenderer.scoreColor(0.5);
		assertThat(half.getRed()).isEqualTo(197);
		assertThat(half.getGreen()).isEqualTo(128);
		assertThat(half.getGreen()).isEqualTo(half.getBlue());
	}
}
