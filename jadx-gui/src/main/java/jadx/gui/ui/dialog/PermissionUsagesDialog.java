package jadx.gui.ui.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.jetbrains.annotations.Nullable;

import jadx.api.metadata.ICodeNodeRef;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.gui.JadxWrapper;
import jadx.gui.jobs.TaskStatus;
import jadx.gui.privacy.ManifestInfo;
import jadx.gui.privacy.PermissionAnnotation;
import jadx.gui.privacy.PermissionDb;
import jadx.gui.privacy.PermissionDb.Requirement;
import jadx.gui.privacy.PermissionUsages;
import jadx.gui.privacy.PermissionUsages.ApiUsage;
import jadx.gui.privacy.PermissionUsages.CallSite;
import jadx.gui.privacy.PermissionUsages.EntryPath;
import jadx.gui.privacy.PrivacyCallGraph;
import jadx.gui.privacy.PrivacyGraphCache;
import jadx.gui.privacy.PrivacyManifestCache;
import jadx.gui.privacy.PrivacyRules;
import jadx.gui.ui.MainWindow;
import jadx.gui.utils.NLS;
import jadx.gui.utils.UiUtils;

/**
 * Permissions declared in the manifest and every place in the code using them, with the call
 * paths from services and receivers. Opened with Ctrl+click on a permission in AndroidManifest.xml.
 */
public class PermissionUsagesDialog extends CommonDialog {
	private static final long serialVersionUID = 4517386283655281173L;

	private static final int MAX_CALL_SITES = 30;
	private static final Pattern PERMISSION_NAME = Pattern.compile("[A-Za-z_][\\w.]*\\.permission\\.[A-Z][A-Z0-9_]*|[A-Z][A-Z0-9_]+");

	private final transient PrivacyRules rules;
	private final transient JadxWrapper wrapper;
	private final transient @Nullable String initialPermission;
	private transient @Nullable PrivacyCallGraph graph;
	private transient ManifestInfo manifest;

	private transient PermissionsModel tableModel;
	private transient JTable table;
	private transient JLabel header;
	private transient JTree tree;
	private transient JLabel statusLabel;

	public PermissionUsagesDialog(MainWindow mainWindow, @Nullable String permission) {
		super(mainWindow);
		this.rules = PrivacyRules.getDefault();
		this.wrapper = mainWindow.getWrapper();
		this.initialPermission = permission;
		this.manifest = PrivacyManifestCache.get(wrapper);
		initUI();
		commonWindowInit();
		setModalityType(ModalityType.MODELESS);
		loadGraph();
	}

	/**
	 * Check if a string from the manifest can be a permission name
	 */
	public static boolean isPermissionName(@Nullable String str) {
		if (str == null || str.isEmpty() || !PERMISSION_NAME.matcher(str).matches()) {
			return false;
		}
		if (str.contains(".permission.")) {
			return true;
		}
		PermissionDb db = PermissionDb.getDefault();
		return db != null && db.getPermission(str) != null;
	}

	private void initUI() {
		setTitle(NLS.str("permission_usages.title"));
		tableModel = new PermissionsModel();
		table = new JTable(tableModel);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setRowSorter(new TableRowSorter<>(tableModel));
		table.getColumnModel().getColumn(0).setPreferredWidth(300);
		table.getColumnModel().getColumn(1).setPreferredWidth(120);
		table.getColumnModel().getColumn(2).setPreferredWidth(60);
		table.setDefaultRenderer(Object.class, new RowRenderer());
		table.setDefaultRenderer(Integer.class, new RowRenderer());
		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				updateDetails();
			}
		});

		header = new JLabel(" ");
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
		tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode(NLS.str("privacy.evidence_loading"))));
		tree.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					TreePath path = tree.getPathForLocation(e.getX(), e.getY());
					if (path != null) {
						Object obj = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
						if (obj instanceof NodeRef) {
							PrivacyOverviewDialog.jumpTo(mainWindow, ((NodeRef) obj).getNodeRef());
						}
					}
				}
			}
		});
		JPanel details = new JPanel(new BorderLayout());
		details.add(header, BorderLayout.NORTH);
		details.add(new JScrollPane(tree), BorderLayout.CENTER);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(table), details);
		split.setResizeWeight(0.35);
		split.setPreferredSize(new Dimension(1100, 600));

		statusLabel = new JLabel(NLS.str("permission_usages.hint"));
		JButton closeBtn = new JButton(NLS.str("common_dialog.close"));
		closeBtn.addActionListener(e -> dispose());
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.add(statusLabel, BorderLayout.CENTER);
		bottom.add(closeBtn, BorderLayout.EAST);

		JPanel content = new JPanel(new BorderLayout(5, 5));
		content.add(split, BorderLayout.CENTER);
		content.add(bottom, BorderLayout.SOUTH);
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		getContentPane().add(content);
	}

	private void loadGraph() {
		PrivacyCallGraph cached = PrivacyGraphCache.getIfBuilt(wrapper, rules);
		if (cached != null) {
			setGraph(cached);
			return;
		}
		AtomicReference<PrivacyCallGraph> result = new AtomicReference<>();
		mainWindow.getBackgroundExecutor().execute(NLS.str("privacy.evidence_loading"),
				() -> result.set(PrivacyGraphCache.get(wrapper, rules, () -> Thread.currentThread().isInterrupted())),
				status -> {
					if (status == TaskStatus.COMPLETE && result.get() != null) {
						setGraph(result.get());
					} else {
						statusLabel.setText(NLS.str("privacy.status_failed", status));
					}
				});
	}

	private void setGraph(PrivacyCallGraph newGraph) {
		this.graph = newGraph;
		Map<String, Integer> counts = PermissionUsages.countApisByPermission(newGraph);
		Set<String> perms = new TreeSet<>(manifest.getUsesPermissions());
		if (initialPermission != null) {
			perms.add(initialPermission);
		}
		List<PermissionRow> rows = new ArrayList<>();
		for (String perm : perms) {
			rows.add(new PermissionRow(perm, protectionLevel(perm), counts.getOrDefault(perm, 0),
					manifest.getUsesPermissions().contains(perm)));
		}
		tableModel.setRows(rows);
		int select = 0;
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).name.equals(initialPermission)) {
				select = i;
			}
		}
		if (!rows.isEmpty()) {
			int viewIdx = table.convertRowIndexToView(select);
			table.getSelectionModel().setSelectionInterval(viewIdx, viewIdx);
			table.scrollRectToVisible(table.getCellRect(viewIdx, 0, true));
		} else {
			tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode(NLS.str("permission_usages.no_permissions"))));
		}
	}

	private void updateDetails() {
		PrivacyCallGraph g = graph;
		int idx = table.getSelectedRow();
		if (g == null || idx == -1) {
			return;
		}
		PermissionRow row = tableModel.getRow(table.convertRowIndexToModel(idx));
		PermissionUsages usages = PermissionUsages.compute(g, manifest, row.name, MAX_CALL_SITES);
		header.setText(buildHeader(row, usages));

		DefaultMutableTreeNode root = new DefaultMutableTreeNode(row.name);
		if (!usages.getManifestUsages().isEmpty()) {
			DefaultMutableTreeNode manifestNode = new DefaultMutableTreeNode("AndroidManifest.xml");
			for (String entry : usages.getManifestUsages()) {
				manifestNode.add(new DefaultMutableTreeNode(entry));
			}
			root.add(manifestNode);
		}
		for (ApiUsage apiUsage : usages.getApis()) {
			root.add(buildApiNode(apiUsage, row.name));
		}
		if (usages.getApis().isEmpty() && usages.getManifestUsages().isEmpty()) {
			root.add(new DefaultMutableTreeNode(NLS.str("permission_usages.unused")));
		}
		tree.setModel(new DefaultTreeModel(root));
		for (int i = 0; i < tree.getRowCount() && i < 300; i++) {
			if (tree.getPathForRow(i).getPathCount() <= 3) {
				tree.expandRow(i);
			}
		}
	}

	private DefaultMutableTreeNode buildApiNode(ApiUsage apiUsage, String permission) {
		StringBuilder title = new StringBuilder(apiUsage.getApi().getLabel());
		List<String> sources = new ArrayList<>();
		for (Requirement req : apiUsage.getApi().getRequirements()) {
			if (req.getPermission().equals(permission)) {
				String src = PermissionAnnotation.sourceName(req.getSource());
				if (req.getMinApi() != null && req.getMaxApi() != null) {
					src += " API " + req.getMinApi() + '–' + req.getMaxApi();
				}
				if (!sources.contains(src)) {
					sources.add(src);
				}
			}
		}
		if (!sources.isEmpty()) {
			title.append("   [").append(String.join("; ", sources)).append(']');
		}
		DefaultMutableTreeNode apiNode = new DefaultMutableTreeNode(title.toString());
		for (CallSite site : apiUsage.getCallSites()) {
			DefaultMutableTreeNode siteNode = new DefaultMutableTreeNode(NodeRef.method(site.getMethod(), ""));
			if (site.getEntries().isEmpty()) {
				siteNode.add(new DefaultMutableTreeNode(NLS.str("permission_usages.no_entry")));
			}
			for (EntryPath entry : site.getEntries()) {
				ClassNode compCls = entry.getComponent().getCls();
				String kind = PrivacyOverviewDialog.kindName(entry.getComponent().getKind());
				DefaultMutableTreeNode compNode = new DefaultMutableTreeNode(NodeRef.cls(compCls, kind + ": "));
				List<MethodNode> path = entry.getPath();
				for (int i = 0; i < path.size(); i++) {
					String prefix = i == 0 ? NLS.str("privacy.entry") + ": " : "→ ";
					compNode.add(new DefaultMutableTreeNode(NodeRef.method(path.get(i), prefix)));
				}
				siteNode.add(compNode);
			}
			apiNode.add(siteNode);
		}
		if (apiUsage.hasMoreCallSites()) {
			apiNode.add(new DefaultMutableTreeNode(NLS.str("privacy.evidence_more", MAX_CALL_SITES)));
		}
		return apiNode;
	}

	private String buildHeader(PermissionRow row, PermissionUsages usages) {
		StringBuilder sb = new StringBuilder("<html><b>").append(UiUtils.escapeHtml(row.name)).append("</b>");
		PermissionDb db = rules.getPermissionDb();
		PermissionDb.PermissionInfo info = db == null ? null : db.getPermission(row.name);
		if (info != null) {
			sb.append(" &nbsp;").append(UiUtils.escapeHtml(NLS.str("permission_usages.protection",
					info.getProtectionLevel() == null ? "?" : info.getProtectionLevel())));
		}
		sb.append("<br>");
		sb.append(UiUtils.escapeHtml(row.declared
				? NLS.str("permission_usages.declared")
				: NLS.str("permission_usages.not_declared")));
		sb.append(" &nbsp;·&nbsp; ").append(UiUtils.escapeHtml(NLS.str("permission_usages.api_count", usages.getApis().size())));
		if (row.declared && usages.getApis().isEmpty() && usages.getManifestUsages().isEmpty()) {
			sb.append("<br><i>").append(UiUtils.escapeHtml(NLS.str("permission_usages.unused_hint"))).append("</i>");
		}
		return sb.append("</html>").toString();
	}

	private @Nullable String protectionLevel(String perm) {
		PermissionDb db = rules.getPermissionDb();
		PermissionDb.PermissionInfo info = db == null ? null : db.getPermission(perm);
		return info == null ? null : info.getProtectionLevel();
	}

	@Override
	public void dispose() {
		graph = null;
		super.dispose();
	}

	/**
	 * Tree node pointing to a class or method
	 */
	private static final class NodeRef {
		private final @Nullable MethodNode mth;
		private final @Nullable ClassNode cls;
		private final String prefix;

		private NodeRef(@Nullable MethodNode mth, @Nullable ClassNode cls, String prefix) {
			this.mth = mth;
			this.cls = cls;
			this.prefix = prefix;
		}

		static NodeRef method(MethodNode mth, String prefix) {
			return new NodeRef(mth, null, prefix);
		}

		static NodeRef cls(ClassNode cls, String prefix) {
			return new NodeRef(null, cls, prefix);
		}

		@Nullable
		ICodeNodeRef getNodeRef() {
			if (mth != null) {
				return mth;
			}
			return cls;
		}

		@Override
		public String toString() {
			if (mth != null) {
				return prefix + mth.getParentClass().getFullName() + '.' + mth.getAlias() + "()";
			}
			return prefix + (cls == null ? "" : cls.getFullName());
		}
	}

	private static final class PermissionRow {
		private final String name;
		private final @Nullable String protection;
		private final int apiCount;
		private final boolean declared;

		private PermissionRow(String name, @Nullable String protection, int apiCount, boolean declared) {
			this.name = name;
			this.protection = protection;
			this.apiCount = apiCount;
			this.declared = declared;
		}
	}

	/**
	 * Unused declared permissions in italic, not declared permissions in gray
	 */
	private final class RowRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected, boolean hasFocus,
				int row, int column) {
			super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
			PermissionRow r = tableModel.getRow(tbl.convertRowIndexToModel(row));
			setFont(getFont().deriveFont(r.apiCount == 0 ? Font.ITALIC : Font.PLAIN));
			if (!isSelected) {
				setForeground(r.declared ? tbl.getForeground() : disabledColor(tbl));
			}
			if (column == 2) {
				setHorizontalAlignment(CENTER);
			} else {
				setHorizontalAlignment(LEFT);
			}
			setToolTipText(r.apiCount == 0 ? NLS.str("permission_usages.unused_hint") : null);
			return this;
		}
	}

	private static Color disabledColor(JTable tbl) {
		Color color = UIManager.getColor("Label.disabledForeground");
		return color != null ? color : tbl.getForeground();
	}

	private static final class PermissionsModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		private static final String[] COLUMNS = {
				NLS.str("permission_usages.col_permission"),
				NLS.str("permission_usages.col_protection"),
				NLS.str("permission_usages.col_apis"),
		};

		private transient List<PermissionRow> rows = Collections.emptyList();

		void setRows(List<PermissionRow> rows) {
			this.rows = rows;
			fireTableDataChanged();
		}

		PermissionRow getRow(int idx) {
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
			return column == 2 ? Integer.class : String.class;
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			PermissionRow row = rows.get(rowIndex);
			switch (columnIndex) {
				case 0:
					return PrivacyRules.shortPermission(row.name);
				case 1:
					return row.protection == null ? "" : row.protection;
				default:
					return row.apiCount;
			}
		}
	}
}
