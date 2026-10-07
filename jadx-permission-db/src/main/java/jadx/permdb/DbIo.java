package jadx.permdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import jadx.permdb.PermissionData.Api;
import jadx.permdb.PermissionData.ApiKind;
import jadx.permdb.PermissionData.Broadcast;
import jadx.permdb.PermissionData.PermissionInfo;
import jadx.permdb.PermissionData.ProviderPermission;
import jadx.permdb.PermissionData.Requirement;
import jadx.permdb.PermissionData.Source;

/**
 * Write and read the SQLite database. The schema is documented in the README
 * ("Android permission database").
 */
final class DbIo {
	static final int SCHEMA_VERSION = 1;

	private static final String[] SCHEMA = {
			"CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
			"CREATE TABLE source (name TEXT PRIMARY KEY, url TEXT NOT NULL, revision TEXT NOT NULL)",
			"CREATE TABLE permission (name TEXT PRIMARY KEY, protection_level TEXT, permission_group TEXT, flags TEXT,"
					+ " source TEXT NOT NULL)",
			"CREATE TABLE api (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, class TEXT NOT NULL, member TEXT NOT NULL,"
					+ " params TEXT, constant TEXT)",
			"CREATE UNIQUE INDEX api_signature ON api (kind, class, member, params)",
			"CREATE INDEX api_constant ON api (constant)",
			"CREATE TABLE api_permission (api_id INTEGER NOT NULL REFERENCES api (id), permission TEXT NOT NULL,"
					+ " mode TEXT NOT NULL, conditional INTEGER NOT NULL, access TEXT, min_api INTEGER, max_api INTEGER,"
					+ " source TEXT NOT NULL)",
			"CREATE INDEX api_permission_api ON api_permission (api_id)",
			"CREATE INDEX api_permission_permission ON api_permission (permission)",
			"CREATE TABLE provider_permission (authority TEXT NOT NULL, path TEXT NOT NULL, path_type TEXT NOT NULL,"
					+ " access TEXT NOT NULL, permission TEXT NOT NULL, package TEXT, source TEXT NOT NULL)",
			"CREATE INDEX provider_permission_authority ON provider_permission (authority)",
			"CREATE TABLE broadcast (action TEXT PRIMARY KEY, permission TEXT, protected INTEGER NOT NULL, source TEXT NOT NULL)",
			"CREATE VIEW api_permission_view AS SELECT a.kind, a.class, a.member, a.params, a.constant,"
					+ " p.permission, p.mode, p.conditional, p.access, p.min_api, p.max_api, p.source"
					+ " FROM api a JOIN api_permission p ON p.api_id = a.id",
	};

	private DbIo() {
	}

	static void write(PermissionData data, Path out) throws IOException {
		Path dir = out.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path tmp = Files.createTempFile(dir, "permissions-", ".db.tmp");
		try {
			Files.delete(tmp); // sqlite creates the file
			try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tmp)) {
				conn.setAutoCommit(false);
				try (Statement st = conn.createStatement()) {
					for (String sql : SCHEMA) {
						st.execute(sql);
					}
				}
				insertAll(conn, data);
				conn.commit();
				conn.setAutoCommit(true);
				try (Statement st = conn.createStatement()) {
					st.execute("VACUUM");
				}
			}
			Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
		} catch (SQLException e) {
			throw new IOException("Failed to write database " + out, e);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	private static void insertAll(Connection conn, PermissionData data) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("INSERT INTO meta VALUES (?, ?)")) {
			for (Map.Entry<String, String> e : data.getMeta().entrySet()) {
				ps.setString(1, e.getKey());
				ps.setString(2, e.getValue());
				ps.addBatch();
			}
			ps.executeBatch();
		}
		try (PreparedStatement ps = conn.prepareStatement("INSERT INTO source VALUES (?, ?, ?)")) {
			for (Source s : data.getSources()) {
				ps.setString(1, s.getName());
				ps.setString(2, s.getUrl());
				ps.setString(3, s.getRevision());
				ps.addBatch();
			}
			ps.executeBatch();
		}
		try (PreparedStatement ps = conn.prepareStatement("INSERT INTO permission VALUES (?, ?, ?, ?, ?)")) {
			for (PermissionInfo p : data.getPermissions()) {
				ps.setString(1, p.getName());
				setNullable(ps, 2, p.getProtectionLevel());
				setNullable(ps, 3, p.getGroup());
				setNullable(ps, 4, p.getFlags());
				ps.setString(5, p.getSource());
				ps.addBatch();
			}
			ps.executeBatch();
		}
		try (PreparedStatement apiPs = conn.prepareStatement("INSERT INTO api VALUES (?, ?, ?, ?, ?, ?)");
				PreparedStatement reqPs = conn.prepareStatement("INSERT INTO api_permission VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
			int id = 0;
			for (Api api : data.getApis()) {
				id++;
				apiPs.setInt(1, id);
				apiPs.setString(2, api.getKind().getDbName());
				apiPs.setString(3, api.getCls());
				apiPs.setString(4, api.getMember());
				setNullable(apiPs, 5, api.getParams());
				setNullable(apiPs, 6, api.getConstant());
				apiPs.addBatch();
				for (Requirement r : api.getRequirements()) {
					reqPs.setInt(1, id);
					reqPs.setString(2, r.getPermission());
					reqPs.setString(3, r.getMode());
					reqPs.setInt(4, r.isConditional() ? 1 : 0);
					setNullable(reqPs, 5, r.getAccess());
					setNullable(reqPs, 6, r.getMinApi());
					setNullable(reqPs, 7, r.getMaxApi());
					reqPs.setString(8, r.getSource());
					reqPs.addBatch();
				}
			}
			apiPs.executeBatch();
			reqPs.executeBatch();
		}
		try (PreparedStatement ps = conn.prepareStatement("INSERT INTO provider_permission VALUES (?, ?, ?, ?, ?, ?, ?)")) {
			for (ProviderPermission p : data.getProviders()) {
				ps.setString(1, p.getAuthority());
				ps.setString(2, p.getPath());
				ps.setString(3, p.getPathType());
				ps.setString(4, p.getAccess());
				ps.setString(5, p.getPermission());
				setNullable(ps, 6, p.getPkg());
				ps.setString(7, p.getSource());
				ps.addBatch();
			}
			ps.executeBatch();
		}
		try (PreparedStatement ps = conn.prepareStatement("INSERT INTO broadcast VALUES (?, ?, ?, ?)")) {
			for (Broadcast b : data.getBroadcasts()) {
				ps.setString(1, b.getAction());
				setNullable(ps, 2, b.getPermission());
				ps.setInt(3, b.isProtectedBroadcast() ? 1 : 0);
				ps.setString(4, b.getSource());
				ps.addBatch();
			}
			ps.executeBatch();
		}
	}

	/**
	 * Read a database written by {@link #write(PermissionData, Path)}
	 */
	static PermissionData read(Path db) throws IOException {
		PermissionData data = new PermissionData();
		try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
				Statement st = conn.createStatement()) {
			try (ResultSet rs = st.executeQuery("SELECT key, value FROM meta")) {
				while (rs.next()) {
					data.putMeta(rs.getString(1), rs.getString(2));
				}
			}
			try (ResultSet rs = st.executeQuery("SELECT name, url, revision FROM source")) {
				while (rs.next()) {
					data.addSource(new Source(rs.getString(1), rs.getString(2), rs.getString(3)));
				}
			}
			try (ResultSet rs = st.executeQuery("SELECT name, protection_level, permission_group, flags, source FROM permission")) {
				while (rs.next()) {
					data.addPermission(
							new PermissionInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
				}
			}
			Map<Integer, Api> apis = new HashMap<>();
			try (ResultSet rs = st.executeQuery("SELECT id, kind, class, member, params, constant FROM api")) {
				while (rs.next()) {
					ApiKind kind = rs.getString(2).equals("field") ? ApiKind.FIELD : ApiKind.METHOD;
					Api api = data.getOrAddApi(kind, rs.getString(3), rs.getString(4), rs.getString(5));
					api.setConstant(rs.getString(6));
					apis.put(rs.getInt(1), api);
				}
			}
			try (ResultSet rs = st.executeQuery("SELECT api_id, permission, mode, conditional, access, min_api, max_api, source"
					+ " FROM api_permission")) {
				while (rs.next()) {
					Api api = apis.get(rs.getInt(1));
					Integer min = getNullableInt(rs, 6);
					Integer max = getNullableInt(rs, 7);
					data.addRequirement(api, rs.getString(2), rs.getString(3), rs.getInt(4) != 0, rs.getString(5), rs.getString(8), min);
					data.addRequirement(api, rs.getString(2), rs.getString(3), rs.getInt(4) != 0, rs.getString(5), rs.getString(8), max);
				}
			}
			try (ResultSet rs = st.executeQuery("SELECT authority, path, path_type, access, permission, package, source"
					+ " FROM provider_permission")) {
				while (rs.next()) {
					data.addProvider(new ProviderPermission(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
							rs.getString(5), rs.getString(6), rs.getString(7)));
				}
			}
			try (ResultSet rs = st.executeQuery("SELECT action, permission, protected, source FROM broadcast")) {
				while (rs.next()) {
					Broadcast b = data.getOrAddBroadcast(rs.getString(1), rs.getString(4));
					b.setPermission(rs.getString(2), rs.getString(4));
					b.setProtectedBroadcast(rs.getInt(3) != 0);
				}
			}
		} catch (SQLException e) {
			throw new IOException("Failed to read database " + db, e);
		}
		return data;
	}

	/**
	 * @return content hash stored in the database meta data, null if not available
	 */
	static @Nullable String readContentHash(Path db) {
		if (!Files.isRegularFile(db)) {
			return null;
		}
		try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
				PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key = 'content_hash'");
				ResultSet rs = ps.executeQuery()) {
			return rs.next() ? rs.getString(1) : null;
		} catch (SQLException e) {
			return null;
		}
	}

	private static @Nullable Integer getNullableInt(ResultSet rs, int col) throws SQLException {
		int value = rs.getInt(col);
		return rs.wasNull() ? null : value;
	}

	private static void setNullable(PreparedStatement ps, int idx, @Nullable String value) throws SQLException {
		if (value == null) {
			ps.setNull(idx, Types.VARCHAR);
		} else {
			ps.setString(idx, value);
		}
	}

	private static void setNullable(PreparedStatement ps, int idx, @Nullable Integer value) throws SQLException {
		if (value == null) {
			ps.setNull(idx, Types.INTEGER);
		} else {
			ps.setInt(idx, value);
		}
	}
}
