package org.tamacat.monitor.server;

import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.tamacat.dao.BindValue;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Row;
import org.tamacat.sql.SqlOperations;

/** Central DB access for {@code zabbix_servers}. Passwords are stored encrypted (see SecretCipher). */
public final class ZabbixServerRepository {

	/** Encrypted passwords of a server. */
	public record EncryptedPasswords(byte[] read, byte[] write) {
	}

	private static final String COLUMNS = "server_id, name, description, db_url, db_read_user, db_write_user, "
			+ "db_write_password is not null as has_write_password, zabbix_version, db_version_mandatory, read_only, "
			+ "enabled, last_status, last_checked_at, created_at, updated_at";

	private static ZabbixServer map(Row r) throws SQLException {
		return new ZabbixServer(r.getLong("server_id"), r.getString("name"), r.getString("description"),
				r.getString("db_url"), r.getString("db_read_user"), r.getString("db_write_user"),
				r.getBoolean("has_write_password"), r.getString("zabbix_version"),
				r.getNullableInt("db_version_mandatory"), r.getBoolean("read_only"), r.getBoolean("enabled"),
				r.getString("last_status"), r.getInstant("last_checked_at"), r.getInstant("created_at"),
				r.getInstant("updated_at"));
	}

	public List<ZabbixServer> findAll(SqlOperations db) {
		return db.queryList(PreparedSql.of("select " + COLUMNS + " from zabbix_servers order by name"),
				ZabbixServerRepository::map);
	}

	public Optional<ZabbixServer> findById(SqlOperations db, long serverId) {
		return db.queryOne(PreparedSql.of("select " + COLUMNS + " from zabbix_servers where server_id = ?", serverId),
				ZabbixServerRepository::map);
	}

	public Optional<ZabbixServer> findByName(SqlOperations db, String name) {
		return db.queryOne(PreparedSql.of("select " + COLUMNS + " from zabbix_servers where name = ?", name),
				ZabbixServerRepository::map);
	}

	public Optional<EncryptedPasswords> findPasswords(SqlOperations db, long serverId) {
		return db.queryOne(PreparedSql.of("select db_read_password, db_write_password from zabbix_servers "
				+ "where server_id = ?", serverId),
				r -> new EncryptedPasswords(r.getBytes("db_read_password"), r.getBytes("db_write_password")));
	}

	/** Inserts with a placeholder password, so that the id can be bound into the encryption purpose. */
	public long insert(SqlOperations db, String name, String description, String dbUrl, String readUser,
			String writeUser, boolean readOnly, boolean enabled, Instant now) {
		return db.insertAndGetKey(PreparedSql.of("insert into zabbix_servers (name, description, db_url, db_read_user, "
				+ "db_read_password, db_write_user, read_only, enabled, created_at, updated_at) "
				+ "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				name, str(description), dbUrl, readUser, new byte[0], str(writeUser), readOnly, enabled, now, now));
	}

	public int update(SqlOperations db, long serverId, String name, String description, String dbUrl,
			String readUser, String writeUser, boolean readOnly, boolean enabled, Instant now) {
		return db.update(PreparedSql.of("update zabbix_servers set name = ?, description = ?, db_url = ?, "
				+ "db_read_user = ?, db_write_user = ?, read_only = ?, enabled = ?, updated_at = ? where server_id = ?",
				name, str(description), dbUrl, readUser, str(writeUser), readOnly, enabled, now, serverId));
	}

	public void updateReadPassword(SqlOperations db, long serverId, byte[] encrypted) {
		db.update(PreparedSql.of("update zabbix_servers set db_read_password = ? where server_id = ?", encrypted, serverId));
	}

	/** @param encrypted null clears the write password */
	public void updateWritePassword(SqlOperations db, long serverId, byte[] encrypted) {
		db.update(PreparedSql.of("update zabbix_servers set db_write_password = ? where server_id = ?",
				encrypted == null ? BindValue.ofNull(Types.VARBINARY) : encrypted, serverId));
	}

	public void updateStatus(SqlOperations db, long serverId, String zabbixVersion, Integer mandatory,
			String status, Instant checkedAt) {
		db.update(PreparedSql.of("update zabbix_servers set zabbix_version = ?, db_version_mandatory = ?, "
				+ "last_status = ?, last_checked_at = ? where server_id = ?",
				str(zabbixVersion), mandatory == null ? BindValue.ofNull(Types.INTEGER) : mandatory, status,
				checkedAt, serverId));
	}

	public int delete(SqlOperations db, long serverId) {
		return db.update(PreparedSql.of("delete from zabbix_servers where server_id = ?", serverId));
	}

	private static Object str(String s) {
		return s == null || s.isBlank() ? BindValue.ofNull(Types.VARCHAR) : s.strip();
	}
}
