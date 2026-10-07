package jadx.gui.ui.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JavaNode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.utils.files.FileUtils;
import jadx.gui.JadxWrapper;
import jadx.gui.jobs.TaskStatus;
import jadx.gui.privacy.ManifestInfo;
import jadx.gui.privacy.PrivacyAnalyzer;
import jadx.gui.privacy.PrivacyCallGraph;
import jadx.gui.privacy.PrivacyGraphCache;
import jadx.gui.privacy.PrivacyManifestCache;
import jadx.gui.privacy.PrivacyReport;
import jadx.gui.privacy.PrivacyReport.Row;
import jadx.gui.privacy.PrivacyReport.RowKind;
import jadx.gui.privacy.PrivacyRules;
import jadx.gui.privacy.PrivacyRules.Api;
import jadx.gui.privacy.PrivacyRules.Capability;
import jadx.gui.settings.JadxProject;
import jadx.gui.ui.MainWindow;
import jadx.gui.utils.NLS;
import jadx.gui.utils.UiUtils;
import jadx.gui.utils.ui.DocumentUpdateListener;

/**
 * Privacy Impact Overview: services/receivers (or classes) with the privacy relevant APIs reachable
 * from them.
 */
public class PrivacyOverviewDialog extends CommonDialog {
	private static final long serialVersionUID = -2481942340530145227L;
	private static final Logger LOG = LoggerFactory.getLogger(PrivacyOverviewDialog.class);

	private static final int MAX_CALL_SITES = 20;
	private static final String[] LIBRARY_PREFIXES = {
			"android.", "androidx.", "com.google.", "kotlin.", "kotlinx.", "j$.", "okhttp3.", "okio.",
			"retrofit2.", "com.squareup.", "io.reactivex.", "org.jetbrains.", "com.facebook.", "org.intellij.",
	};

	private final transient PrivacyRules rules;
	private final transient JadxWrapper wrapper;
	private transient @Nullable PrivacyReport report;
	/** call graph is not saved in project, build on demand for call paths */
	private transient @Nullable PrivacyCallGraph graph;
	private transient boolean graphLoading;

	private transient JCheckBox groupByComponent;
	private transient JCheckBox hideZero;
	private transient JCheckBox hideLibraries;
	private transient JCheckBox directOnly;
	private transient JTextField filterField;
	private transient JButton rerunBtn;
	private transient JLabel statusLabel;
	private transient RowsModel tableModel;
	private transient JTable table;
	private transient JTree evidenceTree;
	private final transient ScoreRenderer scoreRenderer = new ScoreRenderer();

	public PrivacyOverviewDialog(MainWindow mainWindow) {
		super(mainWindow);
		this.rules = PrivacyRules.getDefault();
		this.wrapper = mainWindow.getWrapper();
		this.graph = PrivacyGraphCache.getIfBuilt(wrapper, rules);
		initUI();
		commonWindowInit();
		// keep main window usable to inspect the code
		setModalityType(ModalityType.MODELESS);
		loadOrAnalyze(false);
	}

	private void initUI() {
		setTitle(NLS.str("privacy.title"));

		groupByComponent = new JCheckBox(NLS.str("privacy.group_by_component"), true);
		groupByComponent.setToolTipText(NLS.str("privacy.group_by_component.tooltip"));
		hideZero = new JCheckBox(NLS.str("privacy.hide_zero"), true);
		hideLibraries = new JCheckBox(NLS.str("privacy.hide_libraries"), false);
		hideLibraries.setToolTipText(NLS.str("privacy.hide_libraries.tooltip"));
		directOnly = new JCheckBox(NLS.str("privacy.direct_only"), false);
		directOnly.setToolTipText(NLS.str("privacy.direct_only.tooltip"));
		directOnly.setEnabled(false);
		groupByComponent.addActionListener(e -> {
			directOnly.setEnabled(!groupByComponent.isSelected());
			updateRows();
		});
		hideZero.addActionListener(e -> updateRows());
		hideLibraries.addActionListener(e -> updateRows());
		directOnly.addActionListener(e -> updateRows());
		filterField = new JTextField(15);
		filterField.setToolTipText(NLS.str("privacy.filter"));
		filterField.getDocument().addDocumentListener(new DocumentUpdateListener(e -> updateRows()));
		rerunBtn = new JButton(NLS.str("privacy.rerun"));
		rerunBtn.addActionListener(e -> loadOrAnalyze(true));

		JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
		topBar.add(groupByComponent);
		topBar.add(hideZero);
		topBar.add(hideLibraries);
		topBar.add(directOnly);
		topBar.add(new JLabel(NLS.str("privacy.filter") + ':'));
		topBar.add(filterField);
		topBar.add(rerunBtn);

		tableModel = new RowsModel();
		table = new JTable(tableModel) {
			private static final long serialVersionUID = 1L;

			@Override
			public String getToolTipText(MouseEvent event) {
				int row = rowAtPoint(event.getPoint());
				if (row == -1) {
					return null;
				}
				return tableModel.getRow(convertRowIndexToModel(row)).tooltip;
			}
		};
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		TableRowSorter<RowsModel> sorter = new TableRowSorter<>(tableModel);
		sorter.setSortKeys(Collections.singletonList(new RowSorter.SortKey(1, SortOrder.DESCENDING)));
		table.setRowSorter(sorter);
		table.getColumnModel().getColumn(0).setPreferredWidth(320);
		table.getColumnModel().getColumn(1).setPreferredWidth(50);
		table.getColumnModel().getColumn(2).setPreferredWidth(480);
		table.getColumnModel().getColumn(3).setPreferredWidth(110);
		table.getColumnModel().getColumn(4).setPreferredWidth(90);
		table.getColumnModel().getColumn(1).setCellRenderer(scoreRenderer);
		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				updateEvidence();
			}
		});
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					ViewRow row = getSelectedRow();
					if (row != null && row.cls != null) {
						jumpTo(row.cls.getJavaNode());
					}
				}
			}
		});

		evidenceTree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode(NLS.str("privacy.evidence_hint"))));
		evidenceTree.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					TreePath path = evidenceTree.getPathForLocation(e.getX(), e.getY());
					if (path != null) {
						Object obj = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
						if (obj instanceof MethodRef) {
							jumpTo(((MethodRef) obj).mth.getJavaNode());
						}
					}
				}
			}
		});

		JPanel evidencePanel = new JPanel(new BorderLayout());
		evidencePanel.add(new JLabel(NLS.str("privacy.evidence")), BorderLayout.NORTH);
		evidencePanel.add(new JScrollPane(evidenceTree), BorderLayout.CENTER);

		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), evidencePanel);
		split.setResizeWeight(0.65);
		split.setPreferredSize(new Dimension(1050, 600));

		statusLabel = new JLabel(" ");
		JButton closeBtn = new JButton(NLS.str("common_dialog.close"));
		closeBtn.addActionListener(e -> dispose());
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.add(statusLabel, BorderLayout.CENTER);
		bottom.add(closeBtn, BorderLayout.EAST);

		JPanel content = new JPanel(new BorderLayout(5, 5));
		content.add(topBar, BorderLayout.NORTH);
		content.add(split, BorderLayout.CENTER);
		content.add(bottom, BorderLayout.SOUTH);
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		getContentPane().add(content);
	}

	private void loadOrAnalyze(boolean force) {
		JadxProject project = mainWindow.getProject();
		String inputsHash = FileUtils.buildInputsHash(project.getFilePaths());
		PrivacyReport saved = project.getPrivacyReport();
		if (!force && saved != null && saved.isValidFor(inputsHash, rules)) {
			setReport(saved, NLS.str("privacy.status_saved",
					new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(saved.getCreatedAt()))));
			return;
		}
		rerunBtn.setEnabled(false);
		statusLabel.setText(NLS.str("privacy.status_running"));
		AtomicReference<PrivacyReport> result = new AtomicReference<>();
		AtomicReference<PrivacyCallGraph> resultGraph = new AtomicReference<>();
		mainWindow.getBackgroundExecutor().execute(NLS.str("privacy.title"), () -> {
			ManifestInfo manifest = PrivacyManifestCache.get(wrapper);
			PrivacyCallGraph newGraph = PrivacyGraphCache.build(wrapper, rules, () -> Thread.currentThread().isInterrupted());
			resultGraph.set(newGraph);
			result.set(PrivacyAnalyzer.buildReport(newGraph, manifest, inputsHash));
		}, status -> {
			rerunBtn.setEnabled(true);
			PrivacyReport newReport = result.get();
			if (status != TaskStatus.COMPLETE || newReport == null) {
				statusLabel.setText(NLS.str("privacy.status_failed", status));
				return;
			}
			graph = resultGraph.get();
			project.setPrivacyReport(newReport);
			setReport(newReport, NLS.str("privacy.status_done"));
		});
	}

	private void setReport(PrivacyReport newReport, String status) {
		this.report = newReport;
		statusLabel.setText(status);
		updateRows();
	}

	private void updateRows() {
		PrivacyReport rep = report;
		if (rep == null) {
			return;
		}
		RootNode root = wrapper.getRootNode();
		boolean components = groupByComponent.isSelected();
		boolean direct = !components && directOnly.isSelected();
		String filter = filterField.getText().trim().toLowerCase(Locale.ROOT);
		List<String> excluded = wrapper.getExcludedPackages();

		List<Row> source = new ArrayList<>(components ? rep.getComponents() : rep.getClasses());
		if (!components && !hideZero.isSelected()) {
			// classes without hits are not saved
			Set<String> present = new HashSet<>();
			for (Row row : source) {
				present.add(row.getCls());
			}
			for (ClassNode cls : root.getClassesWithoutInner()) {
				if (!present.contains(cls.getRawName())) {
					source.add(new Row(RowKind.CLASS, cls.getRawName(), false, Collections.emptyList(), Collections.emptyList()));
				}
			}
		}
		List<ViewRow> rows = new ArrayList<>();
		int maxScore = 0;
		for (Row row : source) {
			ViewRow viewRow = new ViewRow(row, root, direct);
			// color scale is relative to all rows of current view, so filters don't change colors
			maxScore = Math.max(maxScore, viewRow.score);
			if (hideZero.isSelected() && viewRow.score == 0) {
				continue;
			}
			if (hideLibraries.isSelected() && isLibrary(viewRow.name)) {
				continue;
			}
			if (isExcluded(viewRow.name, excluded)) {
				continue;
			}
			if (!filter.isEmpty()
					&& !viewRow.name.toLowerCase(Locale.ROOT).contains(filter)
					&& !viewRow.permissions.toLowerCase(Locale.ROOT).contains(filter)) {
				continue;
			}
			rows.add(viewRow);
		}
		scoreRenderer.setMaxScore(maxScore);
		tableModel.setRows(rows);
		updateEvidence();
	}

	private static boolean isLibrary(String name) {
		for (String prefix : LIBRARY_PREFIXES) {
			if (name.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isExcluded(String name, List<String> excluded) {
		for (String pkg : excluded) {
			if (name.equals(pkg) || name.startsWith(pkg + '.')) {
				return true;
			}
		}
		return false;
	}

	private @Nullable ViewRow getSelectedRow() {
		int idx = table.getSelectedRow();
		if (idx == -1) {
			return null;
		}
		return tableModel.getRow(table.convertRowIndexToModel(idx));
	}

	private void updateEvidence() {
		ViewRow row = getSelectedRow();
		if (row == null) {
			evidenceTree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode(NLS.str("privacy.evidence_hint"))));
			return;
		}
		DefaultMutableTreeNode rootNode =
				new DefaultMutableTreeNode(row.name + "  [" + NLS.str("privacy.col_score") + ": " + row.score + ']');
		boolean needGraph = false;
		for (Map.Entry<Capability, List<String>> entry : row.byCapability.entrySet()) {
			Capability cap = entry.getKey();
			String capTitle = cap.getFlag() != null
					? cap.getLabel()
					: cap.getLabel() + "  (+" + cap.getScore() + " × " + entry.getValue().size() + ')';
			DefaultMutableTreeNode capNode = new DefaultMutableTreeNode(capTitle);
			for (String key : entry.getValue()) {
				DefaultMutableTreeNode apiNode = new DefaultMutableTreeNode(rules.getLabelForKey(key));
				capNode.add(apiNode);
				Api api = rules.getApi(key);
				if (api == null || row.cls == null) {
					continue; // manifest entry
				}
				PrivacyCallGraph g = graph;
				if (g == null) {
					needGraph = true;
					apiNode.add(new DefaultMutableTreeNode(NLS.str("privacy.evidence_loading")));
					continue;
				}
				addCallPaths(g, row, api, apiNode);
			}
			rootNode.add(capNode);
		}
		DefaultTreeModel model = new DefaultTreeModel(rootNode);
		evidenceTree.setModel(model);
		for (int i = 0; i < evidenceTree.getRowCount() && i < 200; i++) {
			TreePath path = evidenceTree.getPathForRow(i);
			if (path.getPathCount() <= 3) {
				evidenceTree.expandRow(i);
			}
		}
		if (needGraph) {
			loadGraph();
		}
	}

	private void addCallPaths(PrivacyCallGraph g, ViewRow row, Api api, DefaultMutableTreeNode apiNode) {
		ClassNode cls = row.cls;
		if (cls == null) {
			return;
		}
		Set<MethodNode> roots = row.row.getKind() == RowKind.CLASS
				? PrivacyAnalyzer.classRoots(cls)
				: PrivacyAnalyzer.componentRoots(g, cls);
		List<List<MethodNode>> paths = g.findCallPaths(roots, api.getIndex(), MAX_CALL_SITES);
		for (List<MethodNode> path : paths) {
			if (row.direct && path.size() > 1) {
				continue;
			}
			MethodNode callSite = path.get(path.size() - 1);
			DefaultMutableTreeNode siteNode = new DefaultMutableTreeNode(new MethodRef(callSite, ""));
			if (path.size() > 1) {
				for (int i = 0; i < path.size(); i++) {
					String prefix = i == 0 ? NLS.str("privacy.entry") + ": " : "→ ";
					siteNode.add(new DefaultMutableTreeNode(new MethodRef(path.get(i), prefix)));
				}
			}
			apiNode.add(siteNode);
		}
		if (paths.size() >= MAX_CALL_SITES) {
			apiNode.add(new DefaultMutableTreeNode(NLS.str("privacy.evidence_more", MAX_CALL_SITES)));
		}
	}

	private void loadGraph() {
		if (graphLoading) {
			return;
		}
		graphLoading = true;
		AtomicReference<PrivacyCallGraph> result = new AtomicReference<>();
		mainWindow.getBackgroundExecutor().execute(NLS.str("privacy.evidence_loading"), () -> {
			result.set(PrivacyGraphCache.get(wrapper, rules, () -> Thread.currentThread().isInterrupted()));
		}, status -> {
			graphLoading = false;
			if (status == TaskStatus.COMPLETE && result.get() != null) {
				graph = result.get();
				updateEvidence();
			}
		});
	}

	private void jumpTo(@Nullable JavaNode javaNode) {
		jumpTo(mainWindow, javaNode);
	}

	static void jumpTo(MainWindow mainWindow, @Nullable JavaNode javaNode) {
		if (javaNode == null) {
			return;
		}
		try {
			mainWindow.getTabsController().codeJump(mainWindow.getCacheObject().getNodeCache().makeFrom(javaNode));
		} catch (Exception e) {
			LOG.warn("Failed to jump to node: {}", javaNode, e);
		}
	}

	@Override
	public void dispose() {
		graph = null;
		super.dispose();
	}

	private static final class MethodRef {
		private final MethodNode mth;
		private final String prefix;

		private MethodRef(MethodNode mth, String prefix) {
			this.mth = mth;
			this.prefix = prefix;
		}

		@Override
		public String toString() {
			return prefix + mth.getParentClass().getFullName() + '.' + mth.getAlias() + "()";
		}
	}

	private final class ViewRow {
		private final Row row;
		private final @Nullable ClassNode cls;
		private final String name;
		private final int score;
		private final String permissions;
		private final String kind;
		private final String flags;
		private final String tooltip;
		private final boolean direct;
		/** hit keys grouped by capability, highest score first */
		private final Map<Capability, List<String>> byCapability = new LinkedHashMap<>();

		ViewRow(Row row, RootNode root, boolean direct) {
			this.row = row;
			this.direct = direct;
			this.cls = row.isMissing() ? null : PrivacyAnalyzer.resolveClass(root, row.getCls());
			this.name = cls != null ? cls.getFullName() : row.getCls();
			List<String> hits = direct ? row.getDirectHits() : row.getHits();
			this.score = rules.score(hits);

			Map<Capability, List<String>> groups = new LinkedHashMap<>();
			for (String key : hits) {
				Capability cap = rules.getCapabilityForKey(key);
				if (cap != null) {
					groups.computeIfAbsent(cap, c -> new ArrayList<>()).add(key);
				}
			}
			List<Capability> caps = new ArrayList<>(groups.keySet());
			caps.sort(Comparator.comparingInt(Capability::getScore).reversed().thenComparing(Capability::getLabel));
			StringBuilder perms = new StringBuilder();
			Set<String> flagSet = new TreeSet<>();
			StringBuilder tip = new StringBuilder("<html>");
			for (Capability cap : caps) {
				List<String> keys = groups.get(cap);
				byCapability.put(cap, keys);
				tip.append("<b>").append(UiUtils.escapeHtml(cap.getLabel())).append("</b>");
				if (!cap.getPermissions().isEmpty()) {
					tip.append(" &nbsp;").append(UiUtils.escapeHtml(String.join(", ", cap.getPermissions())));
				}
				tip.append("<br>");
				for (String key : keys) {
					tip.append("&nbsp;&nbsp;&nbsp;").append(UiUtils.escapeHtml(rules.getLabelForKey(key))).append("<br>");
				}
				if (cap.getFlag() != null) {
					flagSet.add(cap.getFlag());
					continue;
				}
				if (perms.length() > 0) {
					perms.append(", ");
				}
				perms.append(cap.getLabel()).append(" ×").append(keys.size());
			}
			if (row.isMissing()) {
				flagSet.add(NLS.str("privacy.flag_missing"));
			}
			this.permissions = perms.toString();
			this.flags = String.join(" ", flagSet);
			this.tooltip = caps.isEmpty() ? null : tip.append("</html>").toString();
			this.kind = kindName(row.getKind());
		}
	}

	/**
	 * Score cell background: white for 0, darker red for higher scores (relative to the highest score)
	 */
	static final class ScoreRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;
		private static final Color ZERO = Color.WHITE;
		private static final Color MAX = new Color(139, 0, 0);

		private int maxScore;

		ScoreRenderer() {
			setHorizontalAlignment(SwingConstants.CENTER);
		}

		void setMaxScore(int maxScore) {
			this.maxScore = maxScore;
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			int score = value instanceof Integer ? (Integer) value : 0;
			double ratio = maxScore <= 0 ? 0 : Math.min(1.0, (double) score / maxScore);
			setBackground(scoreColor(ratio));
			setForeground(ratio > 0.45 ? Color.WHITE : Color.BLACK);
			setFont(getFont().deriveFont(Font.BOLD));
			return this;
		}

		static Color scoreColor(double ratio) {
			return new Color(
					mix(ZERO.getRed(), MAX.getRed(), ratio),
					mix(ZERO.getGreen(), MAX.getGreen(), ratio),
					mix(ZERO.getBlue(), MAX.getBlue(), ratio));
		}

		private static int mix(int from, int to, double ratio) {
			return (int) Math.round(from + (to - from) * ratio);
		}
	}

	static String kindName(RowKind kind) {
		switch (kind) {
			case SERVICE:
				return NLS.str("privacy.kind_service");
			case RECEIVER:
				return NLS.str("privacy.kind_receiver");
			case DYNAMIC_RECEIVER:
				return NLS.str("privacy.kind_dynamic_receiver");
			default:
				return NLS.str("privacy.kind_class");
		}
	}

	private static final class RowsModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		private static final String[] COLUMNS = {
				NLS.str("privacy.col_name"),
				NLS.str("privacy.col_score"),
				NLS.str("privacy.col_permissions"),
				NLS.str("privacy.col_kind"),
				NLS.str("privacy.col_flags"),
		};

		private transient List<ViewRow> rows = Collections.emptyList();

		void setRows(List<ViewRow> rows) {
			this.rows = rows;
			fireTableDataChanged();
		}

		ViewRow getRow(int idx) {
			return rows.get(idx);
		}

		@Override
		public int getRowCount() {
			return rows.size();
		}

		@Override
		public int getColumnCount() {
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column) {
			return COLUMNS[column];
		}

		@Override
		public Class<?> getColumnClass(int column) {
			return column == 1 ? Integer.class : String.class;
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			ViewRow row = rows.get(rowIndex);
			switch (columnIndex) {
				case 0:
					return row.name;
				case 1:
					return row.score;
				case 2:
					return row.permissions;
				case 3:
					return row.kind;
				default:
					return row.flags;
			}
		}
	}
}
