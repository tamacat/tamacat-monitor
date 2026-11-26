package org.tamacat.monitor.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Database;
import org.tamacat.sql.SqlListener;
import org.tamacat.sql.dialect.SqlDialect;
import org.tamacat.sql.pool.ConnectionPool;
import org.tamacat.sql.pool.DriverConnectionFactory;
import org.tamacat.sql.pool.PoolConfig;
import org.tamacat.monitor.audit.AuditLog;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.security.PasswordHasher;
import org.tamacat.monitor.store.migration.Migrator;
import org.tamacat.monitor.store.migration.MigratorTest;
import org.tamacat.monitor.user.UserService.AuthResult;
import org.tamacat.monitor.user.UserService.LoginPolicy;
import org.tamacat.monitor.user.UserService.UserInput;

class UserServiceTest {

	/** A clock the test can move. */
	static final class MutableClock extends Clock {
		Instant now = Instant.parse("2026-11-21T00:00:00Z");

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private ConnectionPool pool;
	private Database db;
	private final MutableClock clock = new MutableClock();
	private UserService service;
	private final Actor admin = new Actor(1, "admin", "127.0.0.1");

	@BeforeEach
	void setUp() {
		String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE";
		pool = new ConnectionPool(PoolConfig.builder("users").maxSize(4).build(),
				new DriverConnectionFactory("org.h2.Driver", url, "sa", () -> "", null));
		db = new Database("users", pool, SqlDialect.MYSQL, Duration.ofSeconds(10), SqlListener.none());
		db.update(PreparedSql.of("create alias get_lock for \"" + MigratorTest.class.getName() + ".getLock\""));
		db.update(PreparedSql.of("create alias release_lock for \"" + MigratorTest.class.getName() + ".releaseLock\""));
		new Migrator(db, getClass().getClassLoader()).migrate();
		service = new UserService(db, new PasswordHasher(10_000), new AuditLog(clock), clock,
				new LoginPolicy(3, Duration.ofMinutes(15)));
		assertTrue(service.bootstrapAdmin("admin", "initial-password-1".toCharArray()));
	}

	@AfterEach
	void tearDown() {
		pool.close();
	}

	private static UserInput input(String login, Role... roles) {
		return new UserInput(login, "User " + login, login + "@example.com", "ja", true, EnumSet.copyOf(List.of(roles)));
	}

	@Test
	void bootstrapOnlyOnceAndMustChangePassword() {
		assertFalse(service.bootstrapAdmin("admin2", "initial-password-2".toCharArray()));
		AuthResult r = service.authenticate("admin", "initial-password-1".toCharArray(), "10.0.0.1");
		User u = assertInstanceOf(AuthResult.Success.class, r).user();
		assertTrue(u.isSystemAdmin());
		assertTrue(u.mustChangePassword());

		service.changeOwnPassword(u.userId(), "initial-password-1".toCharArray(), "a-new-long-password".toCharArray(),
				new Actor(u.userId(), "admin", null));
		assertFalse(service.get(u.userId()).mustChangePassword());
		assertInstanceOf(AuthResult.Failure.class, service.authenticate("admin", "initial-password-1".toCharArray(), null));
		assertInstanceOf(AuthResult.Success.class, service.authenticate("admin", "a-new-long-password".toCharArray(), null));
	}

	@Test
	void clientAddressIsThrottledAcrossAccounts() {
		UserService strict = new UserService(db, new PasswordHasher(10_000), new AuditLog(clock), clock,
				new LoginPolicy(3, Duration.ofMinutes(15), 4, Duration.ofMinutes(10)));
		// password spraying: one failure per (unknown) account, never reaching the account lockout
		for (int i = 0; i < 4; i++) {
			assertInstanceOf(AuthResult.Failure.class, strict.authenticate("user" + i, "wrong-password-x".toCharArray(), "192.0.2.9"));
		}
		// now even the right password is refused from that address, but not from another one
		AuthResult r = strict.authenticate("admin", "initial-password-1".toCharArray(), "192.0.2.9");
		assertEquals("too many failures from the client address", assertInstanceOf(AuthResult.Failure.class, r).reason());
		assertInstanceOf(AuthResult.Success.class, strict.authenticate("admin", "initial-password-1".toCharArray(), "192.0.2.10"));
		// the window passes
		clock.now = clock.now.plus(Duration.ofMinutes(11));
		assertInstanceOf(AuthResult.Success.class, strict.authenticate("admin", "initial-password-1".toCharArray(), "192.0.2.9"));
	}

	@Test
	void lockoutAfterRepeatedFailures() {
		for (int i = 0; i < 3; i++) {
			assertInstanceOf(AuthResult.Failure.class, service.authenticate("admin", "wrong-password-x".toCharArray(), null));
		}
		// correct password is refused while locked
		AuthResult locked = service.authenticate("admin", "initial-password-1".toCharArray(), null);
		assertEquals("locked", assertInstanceOf(AuthResult.Failure.class, locked).reason());
		clock.now = clock.now.plus(Duration.ofMinutes(16));
		assertInstanceOf(AuthResult.Success.class, service.authenticate("admin", "initial-password-1".toCharArray(), null));
		long attempts = db.queryOne(PreparedSql.of("select count(*) from login_attempts"), r -> r.getLong(1)).orElseThrow();
		assertEquals(5, attempts);
		long audits = db.queryOne(PreparedSql.of("select count(*) from audit_log where action = ?", "login"),
				r -> r.getLong(1)).orElseThrow();
		assertEquals(5, audits);
	}

	@Test
	void unknownAndDisabledUsersFail() {
		assertInstanceOf(AuthResult.Failure.class, service.authenticate("nobody", "whatever-password".toCharArray(), null));
		assertInstanceOf(AuthResult.Failure.class, service.authenticate("", "whatever-password".toCharArray(), null));
		User u = service.create(new UserInput("op1", "Operator", null, "en", false, Set.of(Role.OPERATOR)),
				"operator-password".toCharArray(), admin);
		AuthResult r = service.authenticate("op1", "operator-password".toCharArray(), null);
		assertEquals("disabled", assertInstanceOf(AuthResult.Failure.class, r).reason());
		assertTrue(u.mustChangePassword());
	}

	@Test
	void createValidatesAndRejectsDuplicates() {
		ValidationException v = assertThrows(ValidationException.class,
				() -> service.create(new UserInput("x", "", "bad", "fr", true, Set.of()), "short".toCharArray(), admin));
		assertEquals(Set.of("loginId", "displayName", "email", "locale", "roles"), v.fieldErrors().keySet());
		assertThrows(ValidationException.class,
				() -> service.create(input("viewer1", Role.VIEWER), "short".toCharArray(), admin));
		assertThrows(ValidationException.class,
				() -> service.create(input("viewer1", Role.VIEWER), "my-viewer1-password".toCharArray(), admin));
		service.create(input("viewer1", Role.VIEWER), "a-good-password!".toCharArray(), admin);
		assertThrows(ConflictException.class,
				() -> service.create(input("viewer1", Role.VIEWER), "a-good-password!".toCharArray(), admin));
		assertEquals(2, service.findAll().size());
	}

	@Test
	void lastAdministratorIsProtected() {
		long adminId = service.findAll().get(0).userId();
		Actor other = new Actor(999, "other", null);
		assertThrows(ConflictException.class, () -> service.delete(adminId, other));
		assertThrows(ConflictException.class,
				() -> service.update(adminId, input("admin", Role.VIEWER), other));
		assertThrows(ConflictException.class, () -> service.delete(adminId, new Actor(adminId, "admin", null)));

		User second = service.create(input("admin2", Role.SYSTEM_ADMIN), "second-admin-pass".toCharArray(), admin);
		service.update(adminId, input("admin", Role.VIEWER), new Actor(second.userId(), "admin2", null));
		assertEquals(Set.of(Role.VIEWER), service.get(adminId).roles());
	}

	@Test
	void assignmentsAreStored() {
		db.update(PreparedSql.of("insert into zabbix_servers (name, db_url, db_read_user, db_read_password, read_only, "
				+ "enabled, created_at, updated_at) values ('z1', 'jdbc:mysql://h/zabbix', 'r', ?, true, true, ?, ?)",
				new byte[] { 1 }, clock.now, clock.now));
		long serverId = db.queryOne(PreparedSql.of("select server_id from zabbix_servers"), r -> r.getLong(1)).orElseThrow();
		User sa = service.create(input("sa1", Role.SERVER_ADMIN, Role.OPERATOR), "server-admin-pass".toCharArray(), admin);
		service.setAssignments(sa.userId(), Set.of(serverId),
				List.of(new UserScope(serverId, 10, "Linux servers", true)), admin);
		assertEquals(Set.of(serverId), service.serverIds(sa.userId()));
		assertEquals(List.of(new UserScope(serverId, 10, "Linux servers", true)), service.scopes(sa.userId()));
		// dropping SERVER_ADMIN clears server assignments
		service.update(sa.userId(), input("sa1", Role.OPERATOR), admin);
		assertEquals(Set.of(), service.serverIds(sa.userId()));
	}

	@Test
	void resetPasswordForcesChangeAndUnlocks() {
		User v = service.create(input("viewer2", Role.VIEWER), "viewer-password-1".toCharArray(), admin);
		service.changeOwnPassword(v.userId(), "viewer-password-1".toCharArray(), "viewer-password-2".toCharArray(),
				new Actor(v.userId(), "viewer2", null));
		for (int i = 0; i < 3; i++) {
			service.authenticate("viewer2", "nope-nope-nope".toCharArray(), null);
		}
		assertTrue(service.get(v.userId()).isLocked(clock.now));
		service.resetPassword(v.userId(), "viewer-password-3".toCharArray(), admin);
		User after = service.get(v.userId());
		assertFalse(after.isLocked(clock.now));
		assertTrue(after.mustChangePassword());
		assertThrows(ValidationException.class, () -> service.changeOwnPassword(v.userId(),
				"wrong-current-pass".toCharArray(), "viewer-password-4".toCharArray(), admin));
	}
}
