package jadx.gui.ui.codearea;

import java.util.Objects;

import javax.swing.event.HyperlinkEvent;

import org.fife.ui.rsyntaxtextarea.LinkGenerator;
import org.fife.ui.rsyntaxtextarea.LinkGeneratorResult;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JavaNode;
import jadx.gui.treemodel.JNode;
import jadx.gui.utils.JumpPosition;

public class CodeLinkGenerator implements LinkGenerator {
	private static final Logger LOG = LoggerFactory.getLogger(CodeLinkGenerator.class);

	private final CodeArea codeArea;
	private final JNode jNode;

	public CodeLinkGenerator(CodeArea codeArea) {
		this.codeArea = codeArea;
		this.jNode = codeArea.getNode();
	}

	public JavaNode getNodeAtOffset(int offset) {
		try {
			if (!codeArea.getCodeInfo().hasMetadata()) {
				return null;
			}
			int sourceOffset = codeArea.adjustOffsetForWordToken(offset);
			if (sourceOffset == -1) {
				return null;
			}
			return codeArea.getJavaNodeAtOffset(offset);
		} catch (Exception e) {
			LOG.error("getNodeAtOffset error", e);
			return null;
		}
	}

	@Override
	public LinkGeneratorResult isLinkAtOffset(RSyntaxTextArea textArea, int offset) {
		try {
			if (codeArea.isManifest()) {
				return getPermissionLink(offset);
			}
			if (!codeArea.getCodeInfo().hasMetadata()) {
				return null;
			}
			int sourceOffset = codeArea.adjustOffsetForWordToken(offset);
			if (sourceOffset == -1) {
				return null;
			}
			JumpPosition defPos = getJumpBySourceOffset(sourceOffset);
			if (defPos == null) {
				return null;
			}
			return new LinkGeneratorResult() {
				@Override
				public HyperlinkEvent execute() {
					return new HyperlinkEvent(defPos, HyperlinkEvent.EventType.ACTIVATED, null,
							defPos.getNode().makeLongString());
				}

				@Override
				public int getSourceOffset() {
					return sourceOffset;
				}
			};
		} catch (Exception e) {
			LOG.error("isLinkAtOffset error", e);
			return null;
		}
	}

	/**
	 * Permission names in AndroidManifest.xml open the permission usages window
	 */
	private @Nullable LinkGeneratorResult getPermissionLink(int offset) {
		String permission = codeArea.getPermissionAtOffset(offset);
		if (permission == null) {
			return null;
		}
		return new LinkGeneratorResult() {
			@Override
			public HyperlinkEvent execute() {
				codeArea.showPermissionUsages(permission);
				return new HyperlinkEvent(codeArea, HyperlinkEvent.EventType.ACTIVATED, null, permission);
			}

			@Override
			public int getSourceOffset() {
				return offset;
			}
		};
	}

	@Nullable
	private JumpPosition getJumpBySourceOffset(int sourceOffset) {
		final JumpPosition defPos = codeArea.getDefPosForNodeAtOffset(sourceOffset);
		if (defPos == null) {
			return null;
		}
		if (Objects.equals(defPos.getNode().getRootClass(), jNode)
				&& defPos.getPos() == sourceOffset) {
			// ignore self jump
			return null;
		}
		return defPos;
	}
}
