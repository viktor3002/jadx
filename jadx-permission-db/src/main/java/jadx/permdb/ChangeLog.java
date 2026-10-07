package jadx.permdb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jetbrains.annotations.Nullable;

/**
 * Markdown summary of the differences between two database versions (used as pull request text).
 */
final class ChangeLog {
	private static final int MAX_LINES_PER_SECTION = 200;

	private ChangeLog() {
	}

	static String build(@Nullable PermissionData old, PermissionData current) {
		List<String> oldLines = old == null ? Collections.emptyList() : old.canonicalLines();
		List<String> newLines = current.canonicalLines();
		Set<String> oldSet = new HashSet<>(oldLines);
		Set<String> newSet = new HashSet<>(newLines);

		Map<String, List<String>> added = new TreeMap<>();
		Map<String, List<String>> removed = new TreeMap<>();
		for (String line : newLines) {
			if (!oldSet.contains(line)) {
				added.computeIfAbsent(table(line), t -> new ArrayList<>()).add(line);
			}
		}
		for (String line : oldLines) {
			if (!newSet.contains(line)) {
				removed.computeIfAbsent(table(line), t -> new ArrayList<>()).add(line);
			}
		}

		StringBuilder sb = new StringBuilder();
		sb.append("## Android permission database update\n\n");
		Map<String, String> meta = current.getMeta();
		sb.append("- SDK platforms: ").append(meta.getOrDefault("sdk_platforms", "?")).append('\n');
		sb.append("- AOSP tag: ").append(meta.getOrDefault("aosp_tag", "?")).append('\n');
		if (old != null) {
			Map<String, String> oldMeta = old.getMeta();
			sb.append("- previous: SDK ").append(oldMeta.getOrDefault("latest_api", "?"))
					.append(", AOSP ").append(oldMeta.getOrDefault("aosp_tag", "?"))
					.append(", generated ").append(oldMeta.getOrDefault("generated", "?")).append('\n');
		}
		sb.append("- APIs with permissions: ").append(current.getApis().size())
				.append(", permissions: ").append(current.getPermissions().size()).append("\n\n");

		sb.append("| Table | Added | Removed |\n|---|---|---|\n");
		Set<String> tables = new TreeSet<>(added.keySet());
		tables.addAll(removed.keySet());
		for (String t : tables) {
			sb.append("| ").append(t).append(" | ").append(added.getOrDefault(t, Collections.emptyList()).size())
					.append(" | ").append(removed.getOrDefault(t, Collections.emptyList()).size()).append(" |\n");
		}
		appendSection(sb, "Added", added);
		appendSection(sb, "Removed", removed);
		return sb.toString();
	}

	private static void appendSection(StringBuilder sb, String title, Map<String, List<String>> byTable) {
		for (Map.Entry<String, List<String>> e : byTable.entrySet()) {
			List<String> lines = e.getValue();
			sb.append("\n<details><summary>").append(title).append(' ').append(e.getKey())
					.append(" (").append(lines.size()).append(")</summary>\n\n```\n");
			int count = 0;
			for (String line : lines) {
				if (count++ == MAX_LINES_PER_SECTION) {
					sb.append("... ").append(lines.size() - MAX_LINES_PER_SECTION).append(" more\n");
					break;
				}
				sb.append(line.substring(line.indexOf('\t') + 1).replace('\t', ' ')).append('\n');
			}
			sb.append("```\n</details>\n");
		}
	}

	private static String table(String line) {
		int tab = line.indexOf('\t');
		return tab == -1 ? line : line.substring(0, tab);
	}
}
