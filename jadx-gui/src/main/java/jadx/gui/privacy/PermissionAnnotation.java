package jadx.gui.privacy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import jadx.gui.privacy.PermissionDb.PermissionInfo;
import jadx.gui.privacy.PermissionDb.Requirement;
import jadx.gui.privacy.PrivacyApiMatcher.Hit;
import jadx.gui.privacy.PrivacyRules.Capability;
import jadx.gui.utils.NLS;

/**
 * Text of the annotation shown above a highlighted API usage in the code view:
 *
 * <pre>
 * &#64;RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
 * Location · privacy score +1 · dangerous · API 26–37 · Android SDK
 * </pre>
 */
public final class PermissionAnnotation {
	/** preferred source first */
	private static final List<String> SOURCE_ORDER = Arrays.asList(
			"sdk-annotations", "provider-uri", "curated", PermissionDb.SOURCE_RULES, "axplorer");

	private final String annotation;
	private final String details;

	private PermissionAnnotation(String annotation, String details) {
		this.annotation = annotation;
		this.details = details;
	}

	public String getAnnotation() {
		return annotation;
	}

	public String getDetails() {
		return details;
	}

	public static PermissionAnnotation build(Hit hit, @Nullable ManifestInfo manifest, @Nullable PermissionDb db) {
		Capability cap = hit.getApi().getCapability();
		List<Requirement> reqs = selectRequirements(hit.getRequirements());

		StringBuilder details = new StringBuilder();
		if (cap.isPrivacy()) {
			details.append(cap.getLabel()).append(" · ").append(NLS.str("privacy.annotation_score", cap.getScore()));
		} else {
			details.append(NLS.str("privacy.annotation_no_score"));
		}
		Set<String> levels = new LinkedHashSet<>();
		Integer min = null;
		Integer max = null;
		boolean conditional = false;
		for (Requirement req : reqs) {
			PermissionInfo info = db == null ? null : db.getPermission(req.getPermission());
			if (info != null && info.getProtectionLevel() != null) {
				levels.add(info.getProtectionLevel());
			}
			if (req.getMinApi() != null) {
				min = min == null ? req.getMinApi() : Math.min(min, req.getMinApi());
			}
			if (req.getMaxApi() != null) {
				max = max == null ? req.getMaxApi() : Math.max(max, req.getMaxApi());
			}
			conditional |= req.isConditional();
		}
		if (!levels.isEmpty()) {
			details.append(" · ").append(String.join(", ", levels));
		}
		if (min != null && max != null) {
			details.append(" · API ").append(min.equals(max) ? min.toString() : min + "–" + max);
		}
		if (conditional) {
			details.append(" · ").append(NLS.str("privacy.annotation_conditional"));
		}
		if (!reqs.isEmpty()) {
			details.append(" · ").append(sourceName(reqs.get(0).getSource()));
		}
		if (manifest != null && !PrivacyRules.isActive(cap, manifest)) {
			details.append(" · ").append(NLS.str("privacy.code_tooltip_not_declared"));
		}
		return new PermissionAnnotation(formatAnnotation(reqs, cap), details.toString());
	}

	/**
	 * Requirements of the most reliable source, as documented for the newest API level
	 */
	static List<Requirement> selectRequirements(List<Requirement> all) {
		String source = null;
		for (String s : SOURCE_ORDER) {
			for (Requirement req : all) {
				if (req.getSource().equals(s)) {
					source = s;
					break;
				}
			}
			if (source != null) {
				break;
			}
		}
		if (source == null) {
			return all;
		}
		Integer latest = null;
		for (Requirement req : all) {
			if (req.getSource().equals(source) && req.getMaxApi() != null) {
				latest = latest == null ? req.getMaxApi() : Math.max(latest, req.getMaxApi());
			}
		}
		List<Requirement> list = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Requirement req : all) {
			if (req.getSource().equals(source)
					&& (latest == null || Objects.equals(req.getMaxApi(), latest))
					&& seen.add(req.getPermission() + ' ' + req.getAccess())) {
				list.add(req);
			}
		}
		return list;
	}

	private static String formatAnnotation(List<Requirement> reqs, Capability cap) {
		if (reqs.isEmpty()) {
			if (cap.getPermissions().isEmpty()) {
				return cap.getLabel();
			}
			return "@RequiresPermission(" + shortNames(cap.getPermissions()) + ')';
		}
		List<String> read = new ArrayList<>();
		List<String> write = new ArrayList<>();
		List<String> other = new ArrayList<>();
		boolean any = false;
		for (Requirement req : reqs) {
			String access = req.getAccess();
			if ("read".equals(access)) {
				read.add(req.getPermission());
			} else if ("write".equals(access)) {
				write.add(req.getPermission());
			} else {
				other.add(req.getPermission());
				any |= req.isAny();
			}
		}
		StringBuilder sb = new StringBuilder();
		if (!other.isEmpty()) {
			sb.append(format("@RequiresPermission", other, any));
		}
		if (!read.isEmpty()) {
			appendSpace(sb).append(format("@RequiresPermission.Read", read, true));
		}
		if (!write.isEmpty()) {
			appendSpace(sb).append(format("@RequiresPermission.Write", write, true));
		}
		return sb.toString();
	}

	private static StringBuilder appendSpace(StringBuilder sb) {
		if (sb.length() > 0) {
			sb.append("  ");
		}
		return sb;
	}

	private static String format(String name, List<String> perms, boolean any) {
		if (perms.size() == 1) {
			return name + '(' + PrivacyRules.shortPermission(perms.get(0)) + ')';
		}
		return name + '(' + (any ? "anyOf" : "allOf") + " = {" + shortNames(perms) + "})";
	}

	private static String shortNames(List<String> perms) {
		List<String> names = new ArrayList<>(perms.size());
		for (String perm : perms) {
			names.add(PrivacyRules.shortPermission(perm));
		}
		return String.join(", ", names);
	}

	public static String sourceName(String source) {
		switch (source) {
			case "sdk-annotations":
				return NLS.str("privacy.source_sdk");
			case "axplorer":
				return NLS.str("privacy.source_axplorer");
			case "provider-uri":
				return NLS.str("privacy.source_provider");
			case "curated":
				return NLS.str("privacy.source_curated");
			default:
				return NLS.str("privacy.source_rules");
		}
	}
}
