package jadx.gui.privacy;

import org.jetbrains.annotations.Nullable;

import jadx.api.plugins.input.data.ICallSite;
import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.IMethodHandle;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.plugins.input.insns.InsnData;
import jadx.api.plugins.input.insns.Opcode;
import jadx.api.plugins.input.insns.custom.ICustomPayload;
import jadx.core.dex.info.FieldInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.utils.input.InsnDataUtils;
import jadx.gui.privacy.PrivacyApiMatcher.Hit;

/**
 * Scan raw method instructions (no decompilation) for calls, object creation and privacy API usage
 */
public final class PrivacyInsnScanner {

	public interface Visitor {
		/**
		 * @param token name of the API as it appears in decompiled code
		 *              (method or field name, class name for constructors, string constant)
		 */
		void api(Hit hit, String token);

		default void invoke(MethodInfo mthInfo, @Nullable MethodNode target, Opcode opcode) {
		}

		/**
		 * Lambda or method reference ({@code invoke-custom})
		 */
		default void callSite(MethodInfo mthInfo, @Nullable MethodNode target) {
		}

		default void newInstance(ClassNode cls) {
		}
	}

	private final RootNode root;
	private final PrivacyApiMatcher matcher;

	public PrivacyInsnScanner(RootNode root, PrivacyApiMatcher matcher) {
		this.root = root;
		this.matcher = matcher;
	}

	public void scan(MethodNode mth, Visitor visitor) {
		if (mth.isNoCode()) {
			return;
		}
		ICodeReader mthCodeReader = mth.getCodeReader();
		if (mthCodeReader == null) {
			return;
		}
		// use own copy: code reader keeps read position and can be used concurrently by decompilation
		ICodeReader codeReader = mthCodeReader.copy();
		codeReader.visitInstructions(insn -> {
			Opcode opcode = insn.getOpcode();
			if (opcode == Opcode.UNKNOWN) {
				return;
			}
			switch (insn.getIndexType()) {
				case METHOD_REF:
					insn.decode();
					processInvoke(insn, opcode, visitor);
					break;

				case CALL_SITE:
					insn.decode();
					processCallSite(insn, visitor);
					break;

				case TYPE_REF:
					if (opcode == Opcode.NEW_INSTANCE) {
						insn.decode();
						ClassNode cls = root.resolveClass(ArgType.parse(insn.getIndexAsType()));
						if (cls != null) {
							visitor.newInstance(cls);
						}
					}
					break;

				case FIELD_REF:
					insn.decode();
					FieldInfo fieldInfo = FieldInfo.fromRef(root, insn.getIndexAsField());
					report(matcher.matchField(fieldInfo.getDeclClass().getRawName(), fieldInfo.getName()), fieldInfo.getName(), visitor);
					break;

				case STRING_REF:
					insn.decode();
					String str = insn.getIndexAsString();
					report(matcher.matchString(str), str, visitor);
					break;

				default:
					break;
			}
		});
	}

	private void processInvoke(InsnData insn, Opcode opcode, Visitor visitor) {
		IMethodRef mthRef;
		ICustomPayload payload = insn.getPayload();
		if (payload instanceof IMethodRef) {
			mthRef = (IMethodRef) payload;
		} else {
			mthRef = insn.getIndexAsMethod();
		}
		MethodInfo mthInfo = MethodInfo.fromRef(root, mthRef);
		report(matcher.matchMethod(mthInfo), codeToken(mthInfo), visitor);
		visitor.invoke(mthInfo, root.resolveMethod(mthInfo), opcode);
	}

	private void processCallSite(InsnData insn, Visitor visitor) {
		ICallSite callSite = InsnDataUtils.getCallSite(insn);
		IMethodHandle methodHandle = InsnDataUtils.getMethodHandleAt(callSite, 4);
		if (methodHandle == null) {
			return;
		}
		IMethodRef mthRef = methodHandle.getMethodRef();
		if (mthRef == null) {
			return;
		}
		MethodInfo mthInfo = MethodInfo.fromRef(root, mthRef);
		report(matcher.matchMethod(mthInfo), codeToken(mthInfo), visitor);
		visitor.callSite(mthInfo, root.resolveMethod(mthInfo));
	}

	private static void report(@Nullable Hit hit, String token, Visitor visitor) {
		if (hit != null) {
			visitor.api(hit, token);
		}
	}

	/**
	 * Name as it appears in decompiled code: method name, or class short name for constructors
	 */
	private static String codeToken(MethodInfo mthInfo) {
		if (mthInfo.isConstructor()) {
			return mthInfo.getDeclClass().getShortName();
		}
		return mthInfo.getName();
	}
}
