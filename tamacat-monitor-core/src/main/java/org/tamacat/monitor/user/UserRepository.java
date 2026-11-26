package org.tamacat.monitor.user;

import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.tamacat.dao.BindValue;
import org.tamacat.dao.Conditions;
import org.tamacat.dao.PreparedSql;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.sql.Row;
import org.tamacat.sql.SqlOperations;

/** Central DB access for users, roles, server assignments and host group scopes. */
public final class UserRepository {

	/** Password hash and lock state, read only for authentication. */
	public record Credential(long userId, String passwordHash, boolean enabled, int failedCount, Instant lockedUntil) {
	}

	private static final String USER_COLUMNS = "user_id, login_id, display_name, email, locale, enabled, "
			+ "must_change_password, mfa_enabled, locked_until, last_login_at, created_at, updated_at, "
			+ "(select count(*) from user_passkeys p where p.user_id = users.user_id) as passkey_count";

	private static User map(Row r) throws SQLException {
		return new User(r.getLong("user_id"), r.getString("login_id"), r.getString("display_name"),
				r.getString("email"), r.getString("locale"), r.getBoolean("enabled"),
				r.getBoolean("must_change_password"), r.getBoolean("mfa_enabled"), r.getInt("passkey_count"), r.getInstant("locked_until"), r.getInstant("last_login_at"),
				Set.of(), r.getInstant("created_at"), r.getInstant("updated_at"));
	}

	public List<User> findAll(SqlOperations db) {
		List<User> users = db.queryList(PreparedSql.of("select " + USER_COLUMNS + " from users order by login_id"),
				UserRepository::map);
		Map<Long, Set<Role>> roles = new HashMap<>();
		for (var e : db.queryList(PreparedSql.of("select user_id, role_code from user_roles"),
				r -> Map.entry(r.getLong("user_id"), r.getString("role_code")))) {
			Role.parse(e.getValue()).ifPresent(
					role -> roles.computeIfAbsent(e.getKey(), k -> EnumSet.noneOf(Role.class)).add(role));
		}
		List<User> result = new ArrayList<>(users.size());
		for (User u : users) {
			result.add(u.withRoles(roles.getOrDefault(u.userId(), Set.of())));
		}
		return result;
	}

	public Optional<User> findById(SqlOperations db, long userId) {
		return findOne(db, Conditions.eq("user_id", userId));
	}

	public Optional<User> findByLoginId(SqlOperations db, String loginId) {
		return findOne(db, Conditions.eq("login_id", loginId));
	}

	private Optional<User> findOne(SqlOperations db, org.tamacat.dao.Param where) {
		return db.queryOne(SqlBuilder.of("select " + USER_COLUMNS + " from users").where(where).build(),
				UserRepository::map).map(u -> u.withRoles(findRoles(db, u.userId())));
	}

	public Set<Role> findRoles(SqlOperations db, long userId) {
		Set<Role> set = EnumSet.noneOf(Role.class);
		for (String code : db.queryList(PreparedSql.of("select role_code from user_roles where user_id = ?", userId),
				r -> r.getString("role_code"))) {
			Role.parse(code).ifPresent(set::add);
		}
		return set;
	}

	public Optional<Credential> findCredential(SqlOperations db, String loginId) {
		return db.queryOne(PreparedSql.of("select user_id, password_hash, enabled, failed_count, locked_until "
				+ "from users where login_id = ?", loginId),
				r -> new Credential(r.getLong("user_id"), r.getString("password_hash"), r.getBoolean("enabled"),
						r.getInt("failed_count"), r.getInstant("locked_until")));
	}

	public Optional<String> findPasswordHash(SqlOperations db, long userId) {
		return db.queryOne(PreparedSql.of("select password_hash from users where user_id = ?", userId),
				r -> r.getString("password_hash"));
	}

	public long countUsers(SqlOperations db) {
		return db.queryOne(PreparedSql.of("select count(*) from users"), r -> r.getLong(1)).orElse(0L);
	}

	/** Enabled users with SYSTEM_ADMIN. */
	public long countEnabledSystemAdmins(SqlOperations db) {
		return db.queryOne(PreparedSql.of("select count(*) from users u join user_roles r on r.user_id = u.user_id "
				+ "where u.enabled = ? and r.role_code = ?", true, Role.SYSTEM_ADMIN.name()), r -> r.getLong(1)).orElse(0L);
	}

	public long insert(SqlOperations db, String loginId, String displayName, String email, String locale,
			boolean enabled, String passwordHash, boolean mustChangePassword, Instant now) {
		return db.insertAndGetKey(PreparedSql.of("insert into users (login_id, display_name, email, locale, enabled, "
				+ "password_hash, must_change_password, failed_count, password_changed_at, created_at, updated_at) "
				+ "values (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)",
				loginId, displayName, nullableString(email), locale, enabled, passwordHash, mustChangePassword,
				now, now, now));
	}

	public int update(SqlOperations db, long userId, String displayName, String email, String locale,
			boolean enabled, Instant now) {
		return db.update(PreparedSql.of("update users set display_name = ?, email = ?, locale = ?, enabled = ?, "
				+ "updated_at = ? where user_id = ?", displayName, nullableString(email), locale, enabled, now, userId));
	}

	public int updateLocale(SqlOperations db, long userId, String locale, Instant now) {
		return db.update(PreparedSql.of("update users set locale = ?, updated_at = ? where user_id = ?",
				locale, now, userId));
	}

	public int updatePassword(SqlOperations db, long userId, String passwordHash, boolean mustChange, Instant now) {
		return db.update(PreparedSql.of("update users set password_hash = ?, must_change_password = ?, "
				+ "password_changed_at = ?, failed_count = 0, locked_until = null, updated_at = ? where user_id = ?",
				passwordHash, mustChange, now, now, userId));
	}

	public void recordLoginSuccess(SqlOperations db, long userId, Instant now) {
		db.update(PreparedSql.of("update users set failed_count = 0, locked_until = null, last_login_at = ? "
				+ "where user_id = ?", now, userId));
	}

	public void recordLoginFailure(SqlOperations db, long userId, int failedCount, Instant lockedUntil) {
		db.update(PreparedSql.of("update users set failed_count = ?, locked_until = ? where user_id = ?",
				failedCount, lockedUntil == null ? BindValue.ofNull(Types.TIMESTAMP) : lockedUntil, userId));
	}

	public void unlock(SqlOperations db, long userId, Instant now) {
		db.update(PreparedSql.of("update users set failed_count = 0, locked_until = null, updated_at = ? "
				+ "where user_id = ?", now, userId));
	}

	public void insertLoginAttempt(SqlOperations db, String loginId, String clientIp, boolean success, Instant now) {
		db.update(PreparedSql.of("insert into login_attempts (login_id, client_ip, attempted_at, success) "
				+ "values (?, ?, ?, ?)", truncate(loginId, 100), nullableString(clientIp), now, success));
	}

	/** Failed logins from a client address since {@code since} (index ix_login_attempts_ip). */
	public int countFailuresByIp(SqlOperations db, String clientIp, Instant since) {
		return db.queryOne(PreparedSql.of("select count(*) from login_attempts where client_ip = ? and attempted_at > ? "
				+ "and success = false", clientIp, since), r -> r.getInt(1)).orElse(0);
	}

	public void setRoles(SqlOperations db, long userId, Set<Role> roles) {
		db.update(PreparedSql.of("delete from user_roles where user_id = ?", userId));
		for (Role r : roles) {
			db.update(PreparedSql.of("insert into user_roles (user_id, role_code) values (?, ?)", userId, r.name()));
		}
	}

	public int delete(SqlOperations db, long userId) {
		return db.update(PreparedSql.of("delete from users where user_id = ?", userId));
	}

	// ---- assignments ----

	public Set<Long> findServerIds(SqlOperations db, long userId) {
		return Set.copyOf(db.queryList(PreparedSql.of("select server_id from user_servers where user_id = ?", userId),
				r -> r.getLong("server_id")));
	}

	public void setServerIds(SqlOperations db, long userId, Set<Long> serverIds) {
		db.update(PreparedSql.of("delete from user_servers where user_id = ?", userId));
		for (long id : serverIds) {
			db.update(PreparedSql.of("insert into user_servers (user_id, server_id) values (?, ?)", userId, id));
		}
	}

	public List<UserScope> findScopes(SqlOperations db, long userId) {
		return db.queryList(PreparedSql.of("select server_id, hostgroup_id, hostgroup_name, include_subgroups "
				+ "from user_scopes where user_id = ? order by server_id, hostgroup_name", userId),
				r -> new UserScope(r.getLong("server_id"), r.getLong("hostgroup_id"), r.getString("hostgroup_name"),
						r.getBoolean("include_subgroups")));
	}

	public void setScopes(SqlOperations db, long userId, List<UserScope> scopes, Instant now) {
		db.update(PreparedSql.of("delete from user_scopes where user_id = ?", userId));
		for (UserScope s : scopes) {
			db.update(PreparedSql.of("insert into user_scopes (user_id, server_id, hostgroup_id, hostgroup_name, "
					+ "include_subgroups, created_at) values (?, ?, ?, ?, ?, ?)", userId, s.serverId(), s.hostgroupId(),
					truncate(s.hostgroupName(), 255), s.includeSubgroups(), now));
		}
	}

	private static Object nullableString(String s) {
		return s == null || s.isBlank() ? BindValue.ofNull(Types.VARCHAR) : s;
	}

	private static String truncate(String s, int max) {
		return s == null || s.length() <= max ? s : s.substring(0, max);
	}
}
