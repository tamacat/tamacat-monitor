package org.tamacat.monitor.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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
import org.tamacat.monitor.security.webauthn.CoseKey;
import org.tamacat.monitor.security.webauthn.SoftAuthenticator;
import org.tamacat.monitor.security.webauthn.WebAuthn;
import org.tamacat.monitor.store.migration.Migrator;
import org.tamacat.monitor.store.migration.MigratorTest;
import org.tamacat.monitor.user.UserService.LoginPolicy;

class PasskeyServiceTest {

	private static final String RP = "monitor.example.com";
	private static final String ORIGIN = "https://monitor.example.com";
	private static final String PASSWORD = "initial-password-1";

	private ConnectionPool pool;
	private Database db;
	private final UserServiceTest.MutableClock clock = new UserServiceTest.MutableClock();
	private UserService users;
	private PasskeyService passkeys;
	private User admin;
	private final Actor actor = new Actor(1, "admin", "192.0.2.1");

	@BeforeEach
	void setUp() {
		String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE";
		pool = new ConnectionPool(PoolConfig.builder("passkey").maxSize(4).build(),
				new DriverConnectionFactory("org.h2.Driver", url, "sa", () -> "", null));
		db = new Database("passkey", pool, SqlDialect.MYSQL, Duration.ofSeconds(10), SqlListener.none());
		db.update(PreparedSql.of("create alias get_lock for \"" + MigratorTest.class.getName() + ".getLock\""));
		db.update(PreparedSql.of("create alias release_lock for \"" + MigratorTest.class.getName() + ".releaseLock\""));
		new Migrator(db, getClass().getClassLoader()).migrate();
		PasswordHasher hasher = new PasswordHasher(10_000);
		LoginPolicy policy = new LoginPolicy(3, Duration.ofMinutes(15), 10, Duration.ofMinutes(15));
		users = new UserService(db, hasher, new AuditLog(clock), clock, policy);
		users.bootstrapAdmin("admin", PASSWORD.toCharArray());
		admin = users.findAll().get(0);
		passkeys = new PasskeyService(db, new WebAuthn(new WebAuthn.RelyingParty(RP, "tamacat-monitor", Set.of(ORIGIN))), hasher,
				new AuditLog(clock), clock, policy);
	}

	@AfterEach
	void tearDown() {
		pool.close();
	}

	/** Registers a software passkey for the user; returns it with the user handle. */
	private record Registered(SoftAuthenticator authenticator, byte[] userHandle, long passkeyId) {
	}

	private Registered register(User user, String name) {
		SoftAuthenticator a = new SoftAuthenticator(CoseKey.ES256, RP, ORIGIN);
		var options = passkeys.startRegistration(user, PASSWORD.toCharArray(), actor);
		var created = a.create(options.challenge());
		var pk = passkeys.finishRegistration(user, options.challenge(), name, created.clientDataJson(), created.attestationObject(),
				"internal,hybrid", actor);
		return new Registered(a, options.userHandle(), pk.passkeyId());
	}

	private static PasskeyService.AssertionResponse response(SoftAuthenticator.Asserted r, byte[] userHandle) {
		return new PasskeyService.AssertionResponse(r.credentialId(), r.clientDataJson(), r.authenticatorData(), r.signature(), userHandle);
	}

	@Test
	void registerListRenameDelete() {
		assertThrows(ValidationException.class, () -> passkeys.startRegistration(admin, "wrong".toCharArray(), actor));
		Registered r = register(admin, "Laptop");
		assertEquals(1, users.get(admin.userId()).passkeyCount());
		assertTrue(users.get(admin.userId()).hasSecondFactor());
		var list = passkeys.list(admin.userId());
		assertEquals("Laptop", list.get(0).name());

		// the same credential cannot be registered twice; it is excluded on the next registration
		var options = passkeys.startRegistration(admin, PASSWORD.toCharArray(), actor);
		assertEquals(1, options.excludeCredentials().size());
		var again = r.authenticator().create(options.challenge());
		assertThrows(ConflictException.class, () -> passkeys.finishRegistration(admin, options.challenge(), "Again",
				again.clientDataJson(), again.attestationObject(), null, actor));

		// a response to another challenge is refused
		var other = passkeys.startRegistration(admin, PASSWORD.toCharArray(), actor);
		var stale = new SoftAuthenticator(CoseKey.ES256, RP, ORIGIN).create(options.challenge());
		assertThrows(ValidationException.class, () -> passkeys.finishRegistration(admin, other.challenge(), "Stale",
				stale.clientDataJson(), stale.attestationObject(), null, actor));
		assertThrows(ValidationException.class, () -> passkeys.finishRegistration(admin, other.challenge(), " ",
				stale.clientDataJson(), stale.attestationObject(), null, actor));

		passkeys.rename(admin, r.passkeyId(), "Work laptop", actor);
		assertEquals("Work laptop", passkeys.list(admin.userId()).get(0).name());
		assertThrows(ValidationException.class, () -> passkeys.delete(admin, r.passkeyId(), "wrong".toCharArray(), actor));
		passkeys.delete(admin, r.passkeyId(), PASSWORD.toCharArray(), actor);
		assertEquals(0, users.get(admin.userId()).passkeyCount());
	}

	@Test
	void signInWithPasskeyAlone() {
		Registered r = register(admin, "Phone");
		var options = passkeys.startLogin();
		var user = passkeys.login(options.challenge(), response(r.authenticator().get(options.challenge()), r.userHandle()), "192.0.2.9");
		assertEquals(admin.userId(), user.orElseThrow().userId());
		assertTrue(passkeys.list(admin.userId()).get(0).lastUsedAt() != null);

		// replayed response (old challenge), wrong user handle, user verification missing
		var next = passkeys.startLogin();
		assertTrue(passkeys.login(next.challenge(), response(r.authenticator().get(options.challenge()), r.userHandle()), "192.0.2.9").isEmpty());
		assertTrue(passkeys.login(next.challenge(), response(r.authenticator().get(next.challenge()), new byte[32]), "192.0.2.9").isEmpty());
		r.authenticator().userVerified = false;
		assertTrue(passkeys.login(next.challenge(), response(r.authenticator().get(next.challenge()), r.userHandle()), "192.0.2.9").isEmpty());
		r.authenticator().userVerified = true;

		// unknown passkey; disabled user
		var unknown = new SoftAuthenticator(CoseKey.ES256, RP, ORIGIN).get(next.challenge());
		assertTrue(passkeys.login(next.challenge(), response(unknown, null), "192.0.2.9").isEmpty());
		db.update(PreparedSql.of("update users set enabled = false where user_id = ?", admin.userId()));
		assertTrue(passkeys.login(next.challenge(), response(r.authenticator().get(next.challenge()), r.userHandle()), "192.0.2.9").isEmpty());
	}

	@Test
	void failuresFromOneAddressAreThrottled() {
		Registered r = register(admin, "Phone");
		var options = passkeys.startLogin();
		var unknown = new SoftAuthenticator(CoseKey.ES256, RP, ORIGIN).get(options.challenge());
		for (int i = 0; i < 10; i++) {
			assertTrue(passkeys.login(options.challenge(), response(unknown, null), "192.0.2.66").isEmpty());
		}
		// even a valid passkey is refused from that address for a while
		assertTrue(passkeys.login(options.challenge(), response(r.authenticator().get(options.challenge()), r.userHandle()), "192.0.2.66")
				.isEmpty());
		assertTrue(passkeys.login(options.challenge(), response(r.authenticator().get(options.challenge()), r.userHandle()), "192.0.2.67")
				.isPresent());
	}

	@Test
	void secondStepAfterThePassword() throws Exception {
		Registered r = register(admin, "Key");
		var options = passkeys.startSecondFactor(admin.userId());
		assertEquals(1, options.allowCredentials().size());
		r.authenticator().userVerified = false; // not needed after the password
		assertTrue(passkeys.verifySecondFactor(admin, options.challenge(), response(r.authenticator().get(options.challenge()), null), actor));

		// another user's passkey does not count; wrong responses lock the account (3 failures in this policy)
		User other = users.create(new UserService.UserInput("op.test", "Op", null, "en", true, Set.of(Role.OPERATOR)),
				"another-password-9".toCharArray(), actor);
		SoftAuthenticator foreign = new SoftAuthenticator(CoseKey.ES256, RP, ORIGIN);
		var o = passkeys.startRegistration(other, "another-password-9".toCharArray(), actor);
		var created = foreign.create(o.challenge());
		passkeys.finishRegistration(other, o.challenge(), "Other", created.clientDataJson(), created.attestationObject(), null, actor);
		for (int i = 0; i < 3; i++) {
			assertFalse(passkeys.verifySecondFactor(admin, options.challenge(), response(foreign.get(options.challenge()), null), actor));
		}
		assertTrue(users.get(admin.userId()).isLocked(clock.instant()));
		assertFalse(passkeys.verifySecondFactor(users.get(admin.userId()), options.challenge(),
				response(r.authenticator().get(options.challenge()), null), actor));
	}

	@Test
	void administratorResetRemovesAllPasskeys() {
		register(admin, "One");
		register(admin, "Two");
		assertEquals(2, users.get(admin.userId()).passkeyCount());
		passkeys.deleteAll(admin, actor);
		assertEquals(0, users.get(admin.userId()).passkeyCount());
		assertFalse(users.get(admin.userId()).hasSecondFactor());
	}

	@Test
	void disabledWithoutRelyingParty() {
		PasskeyService off = new PasskeyService(db, null, new PasswordHasher(10_000), new AuditLog(clock), clock, LoginPolicy.DEFAULT);
		assertFalse(off.enabled());
		assertThrows(ConflictException.class, off::startLogin);
	}
}
