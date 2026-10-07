package jadx.gui.ui.codearea;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rtextarea.ChangeableHighlightPainter;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.ICodeInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.gui.JadxWrapper;
import jadx.gui.privacy.ManifestInfo;
import jadx.gui.privacy.PermissionAnnotation;
import jadx.gui.privacy.PrivacyCodeMarks;
import jadx.gui.privacy.PrivacyCodeMarks.Mark;
import jadx.gui.privacy.PrivacyManifestCache;
import jadx.gui.privacy.PrivacyRules;
import jadx.gui.treemodel.JClass;
import jadx.gui.utils.UiUtils;

/**
 * Highlight usages of APIs which need a permission: light red for privacy relevant permissions,
 * light amber for other permissions. While the mouse is over a highlight, an annotation with the
 * required permission is painted above it.
 */
final class PrivacyHighlighter extends MouseAdapter {
	private static final Logger LOG = LoggerFactory.getLogger(PrivacyHighlighter.class);

	static final Color PRIVACY_COLOR = new Color(255, 0, 0);
	static final Color PERMISSION_COLOR = new Color(255, 170, 0);
	private static final float HIGHLIGHT_ALPHA = 0.25f;
	private static final int PAD = 4;

	private final CodeArea codeArea;
	private final JClass jCls;
	private final ChangeableHighlightPainter privacyPainter = new ChangeableHighlightPainter(PRIVACY_COLOR, true, HIGHLIGHT_ALPHA);
	private final ChangeableHighlightPainter permissionPainter =
			new ChangeableHighlightPainter(PERMISSION_COLOR, true, HIGHLIGHT_ALPHA);
	private final List<Object> tags = new ArrayList<>();
	private List<Mark> marks = Collections.emptyList();
	private @Nullable ManifestInfo manifest;
	private @Nullable Mark hovered;
	private @Nullable PermissionAnnotation hoveredAnnotation;
	private @Nullable Rectangle overlayBounds;

	PrivacyHighlighter(CodeArea codeArea, JClass jCls) {
		this.codeArea = codeArea;
		this.jCls = jCls;
	}

	/**
	 * Recompute highlights for the current code, call after code text was set
	 */
	void update() {
		// old positions are invalid for the new text
		apply(Collections.emptyList(), manifest);
		ICodeInfo codeInfo = codeArea.getCodeInfo();
		ClassNode cls = jCls.getRootClass().getCls().getClassNode();
		JadxWrapper wrapper = codeArea.getMainWindow().getWrapper();
		UiUtils.bgRun(() -> {
			try {
				ManifestInfo manifestInfo = PrivacyManifestCache.get(wrapper);
				List<Mark> newMarks = PrivacyCodeMarks.compute(cls, codeInfo, PrivacyRules.getDefault());
				UiUtils.uiRun(() -> {
					if (!codeArea.isDisposed() && codeArea.getCodeInfo() == codeInfo) {
						apply(newMarks, manifestInfo);
					}
				});
			} catch (Exception e) {
				LOG.warn("Failed to compute privacy highlights for {}", cls, e);
			}
		});
	}

	private void apply(List<Mark> newMarks, @Nullable ManifestInfo manifestInfo) {
		for (Object tag : tags) {
			codeArea.getHighlighter().removeHighlight(tag);
		}
		tags.clear();
		setHovered(null);
		this.manifest = manifestInfo;
		this.marks = newMarks;
		int len = codeArea.getDocument().getLength();
		for (Mark mark : newMarks) {
			if (mark.getEnd() > len) {
				continue;
			}
			try {
				ChangeableHighlightPainter painter = mark.isPrivacy() ? privacyPainter : permissionPainter;
				tags.add(codeArea.getHighlighter().addHighlight(mark.getStart(), mark.getEnd(), painter));
			} catch (Exception e) {
				LOG.debug("Failed to add privacy highlight", e);
			}
		}
	}

	private @Nullable Mark getMarkAt(MouseEvent e) {
		if (marks.isEmpty()) {
			return null;
		}
		int offset = codeArea.viewToModel2D(e.getPoint());
		for (Mark mark : marks) {
			if (offset < mark.getStart()) {
				return null; // sorted by start
			}
			if (offset <= mark.getEnd()) {
				// mouse can be after the end of the line
				Rectangle bounds = getBounds(mark);
				return bounds != null && bounds.contains(e.getPoint()) ? mark : null;
			}
		}
		return null;
	}

	private @Nullable Rectangle getBounds(Mark mark) {
		try {
			Rectangle2D start = codeArea.modelToView2D(mark.getStart());
			Rectangle2D end = codeArea.modelToView2D(mark.getEnd());
			if (start == null || end == null) {
				return null;
			}
			Rectangle r = start.getBounds();
			r.add(end.getBounds());
			return r;
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public void mouseMoved(MouseEvent e) {
		setHovered(getMarkAt(e));
	}

	@Override
	public void mouseExited(MouseEvent e) {
		setHovered(null);
	}

	private void setHovered(@Nullable Mark mark) {
		if (mark == hovered) {
			return;
		}
		hovered = mark;
		hoveredAnnotation = mark == null
				? null
				: PermissionAnnotation.build(mark.getHit(), manifest, PrivacyRules.getDefault().getPermissionDb());
		if (overlayBounds != null) {
			codeArea.repaint(overlayBounds);
			overlayBounds = null;
		}
		if (mark != null) {
			// bounds are known after painting, repaint visible area
			codeArea.repaint(codeArea.getVisibleRect());
		}
	}

	/**
	 * Paint the annotation of the hovered usage above the code line (below if no space above)
	 */
	void paintOverlay(Graphics g) {
		Mark mark = hovered;
		PermissionAnnotation annotation = hoveredAnnotation;
		if (mark == null || annotation == null) {
			return;
		}
		Rectangle markBounds = getBounds(mark);
		if (markBounds == null) {
			return;
		}
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			Font font = codeArea.getFont();
			Font detailsFont = font.deriveFont(Font.ITALIC, font.getSize2D() * 0.9f);
			FontMetrics fm = g2.getFontMetrics(font);
			FontMetrics dfm = g2.getFontMetrics(detailsFont);
			String line1 = annotation.getAnnotation();
			String line2 = annotation.getDetails();
			int width = Math.max(fm.stringWidth(line1), dfm.stringWidth(line2)) + 2 * PAD;
			int height = fm.getHeight() + dfm.getHeight() + 2 * PAD;

			Rectangle visible = codeArea.getVisibleRect();
			int x = Math.max(visible.x, Math.min(markBounds.x - PAD, visible.x + visible.width - width - 1));
			int y = markBounds.y - height - 1;
			if (y < visible.y) {
				y = markBounds.y + markBounds.height + 1;
			}
			Rectangle box = new Rectangle(x, y, width, height);
			overlayBounds = new Rectangle(x - 1, y - 1, width + 3, height + 3);

			Color highlight = mark.isPrivacy() ? PRIVACY_COLOR : PERMISSION_COLOR;
			Color background = codeArea.getBackground();
			g2.setColor(mix(background, highlight, 0.12));
			g2.fillRoundRect(box.x, box.y, box.width, box.height, 6, 6);
			g2.setColor(mix(background, highlight, 0.6));
			g2.drawRoundRect(box.x, box.y, box.width, box.height, 6, 6);

			g2.setFont(font);
			g2.setColor(getStyleColor(TokenTypes.ANNOTATION, codeArea.getForeground()));
			g2.drawString(line1, box.x + PAD, box.y + PAD + fm.getAscent());
			g2.setFont(detailsFont);
			g2.setColor(getStyleColor(TokenTypes.COMMENT_EOL, codeArea.getForeground()));
			g2.drawString(line2, box.x + PAD, box.y + PAD + fm.getHeight() + dfm.getAscent());
		} finally {
			g2.dispose();
		}
	}

	private Color getStyleColor(int tokenType, Color fallback) {
		SyntaxScheme scheme = codeArea.getSyntaxScheme();
		Style style = scheme == null ? null : scheme.getStyle(tokenType);
		return style != null && style.foreground != null ? style.foreground : fallback;
	}

	private static Color mix(Color a, Color b, double ratio) {
		return new Color(
				(int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * ratio),
				(int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * ratio),
				(int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * ratio));
	}
}
