package jadx.gui.privacy;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of the privacy analysis. Saved in the jadx project file to avoid re-running the analysis.
 */
public class PrivacyReport {
	public static final int FORMAT_VERSION = 1;

	public enum RowKind {
		SERVICE, RECEIVER, DYNAMIC_RECEIVER, CLASS
	}

	public static class Row {
		private RowKind kind;
		/** raw class name */
		private String cls;
		/** class declared in manifest but not found in the app code */
		private boolean missing;
		/**
		 * keys of all reachable privacy APIs and manifest entries (see
		 * {@link PrivacyRules#getCapabilityForKey})
		 */
		private List<String> hits = new ArrayList<>();
		/** keys of privacy APIs used directly by the class own methods (only for {@link RowKind#CLASS}) */
		private List<String> directHits = new ArrayList<>();

		public Row() {
			// for gson
		}

		public Row(RowKind kind, String cls, boolean missing, List<String> hits, List<String> directHits) {
			this.kind = kind;
			this.cls = cls;
			this.missing = missing;
			this.hits = hits;
			this.directHits = directHits;
		}

		public RowKind getKind() {
			return kind;
		}

		public String getCls() {
			return cls;
		}

		public boolean isMissing() {
			return missing;
		}

		public List<String> getHits() {
			return hits;
		}

		public List<String> getDirectHits() {
			return directHits;
		}
	}

	private int formatVersion = FORMAT_VERSION;
	private String inputsHash;
	private String rulesFingerprint;
	private long createdAt;
	private List<Row> components = new ArrayList<>();
	/**
	 * only classes with at least one hit, classes without hits are added from the class list on display
	 */
	private List<Row> classes = new ArrayList<>();

	public PrivacyReport() {
		// for gson
	}

	public PrivacyReport(String inputsHash, String rulesFingerprint, List<Row> components, List<Row> classes) {
		this.inputsHash = inputsHash;
		this.rulesFingerprint = rulesFingerprint;
		this.createdAt = System.currentTimeMillis();
		this.components = components;
		this.classes = classes;
	}

	/**
	 * Check if saved report still matches the current inputs and rules
	 */
	public boolean isValidFor(String currentInputsHash, PrivacyRules rules) {
		return formatVersion == FORMAT_VERSION
				&& currentInputsHash.equals(inputsHash)
				&& rules.getFingerprint().equals(rulesFingerprint);
	}

	public long getCreatedAt() {
		return createdAt;
	}

	public List<Row> getComponents() {
		return components;
	}

	public List<Row> getClasses() {
		return classes;
	}
}
