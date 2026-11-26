package org.tamacat.monitor.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import org.tamacat.monitor.security.SecretCipher;
import org.tamacat.monitor.security.Totp;
import org.tamacat.monitor.store.migration.Migrator;
import org.tamacat.monitor.store.migration.MigratorTest;
import org.tamacat.monitor.user.UserService.LoginPolicy;

class MfaServiceTest {

	private ConnectionPool pool;
	private Database db;
	private final UserServiceTest.MutableClock clock = new UserServiceTest.MutableClock();
	private UserService users;
	private MfaService mfa;
	private User admin;
	private final Actor actor = new Actor(1, "admin", "192.0.2.1");

	@BeforeEach
	void setUp() {
		String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE";
		pool = new ConnectionPool(PoolConfig.builder("mfa").maxSize(4).build(),
				new DriverConnectionFactory("org.h2.Driver", url, "sa", () -> "", null));
		db = new Database("mfa", pool, SqlDialect.MYSQL, Duration.ofSeconds(10), SqlListener.none());
		db.update(PreparedSql.of("create alias get_lock for \"" + MigratorTest.class.getName() + ".getLock\""));
		db.update(PreparedSql.of("create alias release_lock for \"" + MigratorTest.class.getName() + ".releaseLock\""));
		new Migrator(db, getClass().getClassLoader()).migrate();
		PasswordHasher hasher = new PasswordHasher(10_000);
		LoginPolicy policy = new LoginPolicy(3, Duration.ofMinutes(15));
		users = new UserService(db, hasher, new AuditLog(clock), clock, policy);
		users.bootstrapAdmin("admin", "initial-password-1".toCharArray());
		admin = users.findAll().get(0);
		mfa = new MfaService(db, new SecretCipher(new byte[32]), hasher, new AuditLog(clock), clock, policy, "tmon test");
	}

	@AfterEach
	void tearDown() {
		pool.close();
	}

	@Test
	void totpMatchesRfc6238() {
		byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
		// RFC 6238 appendix B (SHA1, 8 digits): 94287082, 07081804, 14050471 -> the last 6 digits
		assertEquals("287082", Totp.code(secret, Totp.step(Instant.ofEpochSecond(59))));
		assertEquals("081804", Totp.code(secret, Totp.step(Instant.ofEpochSecond(1111111109))));
		assertEquals("050471", Totp.code(secret, Totp.step(Instant.ofEpochSecond(1111111111))));
		assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.base32(secret));
		assertEquals(new String(secret, StandardCharsets.US_ASCII),
				new String(Totp.fromBase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"), StandardCharsets.US_ASCII));
	}

	private String codeNow(String secret) {
		return Totp.code(Totp.fromBase32(secret), Totp.step(clock.instant()));
	}

	@Test
	void enableLoginRecoveryAndDisable() {
		MfaService.Setup setup = mfa.newSetup(admin);
		assertTrue(setup.uri().startsWith("otpauth://totp/tmon%20test:admin?secret=" + setup.secret()));
		// wrong password / wrong code
		assertThrows(ValidationException.class, () -> mfa.enable(admin, setup.secret(), "wrong-password-xx".toCharArray(),
				codeNow(setup.secret()), actor));
		assertThrows(ValidationException.class, () -> mfa.enable(admin, setup.secret(), "initial-password-1".toCharArray(), "000000", actor));
		List<String> recovery = mfa.enable(admin, setup.secret(), "initial-password-1".toCharArray(), codeNow(setup.secret()), actor);
		assertEquals(10, recovery.size());
		User u = users.get(admin.userId());
		assertTrue(u.mfaEnabled());
		assertThrows(ConflictException.class, () -> mfa.enable(u, setup.secret(), "initial-password-1".toCharArray(), "1", actor));

		// the code used for set-up is not accepted again; the next step's code is
		assertFalse(mfa.verifyLogin(u, codeNow(setup.secret()), actor));
		clock.now = clock.now.plusSeconds(30);
		assertTrue(mfa.verifyLogin(u, codeNow(setup.secret()), actor));
		assertFalse(mfa.verifyLogin(u, codeNow(setup.secret()), actor), "replay");

		// a recovery code works once (any case, with or without the dash)
		assertTrue(mfa.verifyLogin(u, recovery.get(0).toUpperCase().replace("-", ""), actor));
		assertFalse(mfa.verifyLogin(u, recovery.get(0), actor));
		assertEquals(9, mfa.remainingRecoveryCodes(u.userId()));

		clock.now = clock.now.plusSeconds(30);
		mfa.disable(u, "initial-password-1".toCharArray(), codeNow(setup.secret()), actor);
		assertFalse(users.get(u.userId()).mfaEnabled());
		assertEquals(0, mfa.remainingRecoveryCodes(u.userId()));
	}

	@Test
	void wrongCodesLockTheAccountAndAdminCanReset() {
		MfaService.Setup setup = mfa.newSetup(admin);
		mfa.enable(admin, setup.secret(), "initial-password-1".toCharArray(), codeNow(setup.secret()), actor);
		User u = users.get(admin.userId());
		for (int i = 0; i < 3; i++) {
			assertFalse(mfa.verifyLogin(u, "123456", actor));
		}
		User locked = users.get(u.userId());
		assertTrue(locked.isLocked(clock.instant()));
		clock.now = clock.now.plusSeconds(30);
		assertFalse(mfa.verifyLogin(locked, codeNow(setup.secret()), actor), "locked accounts are refused");
		mfa.reset(locked, actor);
		assertFalse(users.get(u.userId()).mfaEnabled());
	}
}
