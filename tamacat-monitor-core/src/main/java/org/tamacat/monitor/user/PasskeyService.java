package org.tamacat.monitor.user;

import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.tamacat.dao.BindValue;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Database;
import org.tamacat.monitor.audit.AuditLog;
import org.tamacat.monitor.audit.AuditLog.Result;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.security.PasswordHasher;
import org.tamacat.monitor.security.webauthn.WebAuthn;
import org.tamacat.monitor.security.webauthn.WebAuthnException;
import org.tamacat.monitor.user.UserService.LoginPolicy;

/**
 * Passkeys (WebAuthn, T-801), used two ways:
 * <ul>
 * <li>Sign-in without login ID and password: a discoverable credential with user verification (biometrics
 * or PIN), so the passkey alone is a multi-factor sign-in.</li>
 * <li>Second step after the password, like a TOTP code (user verification not required).</li>
 * </ul>
 * Registering needs the current password. A user who has a passkey or TOTP is asked for the second step
 * after the password. A system administrator's 2FA reset also deletes the user's passkeys.
 * <p>
 * Failed passwordless sign-ins count against the client address (as failed passwords do); a failed second
 * step counts against the account, as a wrong TOTP code does.
 */
public final class PasskeyService {

	/** Longest passkey name. */
	public static final int MAX_NAME = 100;
	/** Most passkeys per user. */
	public static final int MAX_PER_USER = 20;

	public record Passkey(long passkeyId, String name, int algorithm, boolean backedUp, Instant createdAt, Instant lastUsedAt) {
	}

	/** Data for navigator.credentials.create (the challenge is kept by the caller until the response). */
	public record CreationOptions(byte[] challenge, byte[] userHandle, List<byte[]> excludeCredentials) {
	}

	/** Data for navigator.credentials.get. */
	public record RequestOptions(byte[] challenge, List<byte[]> allowCredentials) {
	}

	/** A response of navigator.credentials.get. */
	public record AssertionResponse(byte[] credentialId, byte[] clientDataJson, byte[] authenticatorData, byte[] signature,
			byte[] userHandle) {
	}

	private record Stored(long passkeyId, long userId, byte[] publicKey, long signCount) {
	}

	private final Database db;
	private final WebAuthn webauthn;
	private final PasswordHasher hasher;
	private final AuditLog audit;
	private final Clock clock;
	private final LoginPolicy policy;
	private final UserRepository repo = new UserRepository();

	/** @param webauthn null when passkeys are not configured (no RP ID): every operation is refused */
	public PasskeyService(Database db, WebAuthn webauthn, PasswordHasher hasher, AuditLog audit, Clock clock, LoginPolicy policy) {
		this.db = db;
		this.webauthn = webauthn;
		this.hasher = hasher;
		this.audit = audit;
		this.clock = clock;
		this.policy = policy;
	}

	public boolean enabled() {
		return webauthn != null;
	}

	public WebAuthn.RelyingParty relyingParty() {
		return rp().relyingParty();
	}

	public List<Passkey> list(long userId) {
		return db.queryList(PreparedSql.of("select passkey_id, name, algorithm, backed_up, created_at, last_used_at from user_passkeys "
				+ "where user_id = ? order by created_at, passkey_id", userId),
				r -> new Passkey(r.getLong("passkey_id"), r.getString("name"), r.getInt("algorithm"), r.getBoolean("backed_up"),
						r.getInstant("created_at"), r.getInstant("last_used_at")));
	}

	// ---- registration (account page) ----

	/** Checks the password and returns the creation options; the user handle is created on first use. */
	public CreationOptions startRegistration(User user, char[] password, Actor actor) {
		try {
			rp();
			requirePassword(user, password, actor);
			return db.inTransaction(tx -> {
				byte[] handle = tx.queryOne(PreparedSql.of("select webauthn_user_id from users where user_id = ? for update", user.userId()),
						r -> r.getBytes(1)).orElse(null);
				if (handle == null) {
					handle = WebAuthn.newChallenge(); // 32 random bytes
					tx.update(PreparedSql.of("update users set webauthn_user_id = ? where user_id = ?", handle, user.userId()));
				}
				List<byte[]> exclude = tx.queryList(PreparedSql.of("select credential_id from user_passkeys where user_id = ?",
						user.userId()), r -> r.getBytes(1));
				if (exclude.size() >= MAX_PER_USER) {
					throw new ConflictException("error.passkey.tooMany");
				}
				return new CreationOptions(WebAuthn.newChallenge(), handle, exclude);
			});
		} finally {
			PasswordHasher.wipe(password);
		}
	}

	/** Verifies the authenticator's response to {@link #startRegistration} and stores the passkey. */
	public Passkey finishRegistration(User user, byte[] challenge, String name, byte[] clientDataJson, byte[] attestationObject,
			String transports, Actor actor) {
		String n = validName(name);
		WebAuthn.NewCredential c;
		try {
			c = rp().verifyRegistration(clientDataJson, attestationObject, challenge, true);
		} catch (WebAuthnException e) {
			audit(actor, user, "addPasskey", Result.FAILURE, e.getMessage());
			throw ValidationException.of("credential", "error.passkey.invalid");
		}
		Instant now = clock.instant();
		String tr = transports == null || transports.isBlank() ? null : transports.strip();
		if (tr != null && (tr.length() > 200 || !tr.matches("[a-z0-9,-]+"))) {
			tr = null;
		}
		String transportList = tr;
		long id = db.inTransaction(tx -> {
			if (tx.queryOne(PreparedSql.of("select 1 from user_passkeys where credential_id = ?", c.credentialId()), r -> 1).isPresent()) {
				throw new ConflictException("error.passkey.duplicate");
			}
			tx.update(PreparedSql.of("insert into user_passkeys (user_id, credential_id, public_key, algorithm, sign_count, name, "
					+ "transports, backup_eligible, backed_up, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", user.userId(),
					c.credentialId(), c.publicKey(), c.algorithm(), c.signCount(), n,
					transportList == null ? BindValue.ofNull(Types.VARCHAR) : transportList, c.backupEligible(), c.backedUp(), now));
			long newId = tx.queryOne(PreparedSql.of("select passkey_id from user_passkeys where credential_id = ?", c.credentialId()),
					r -> r.getLong(1)).orElseThrow();
			audit.record(tx, actor, null, "user", user.loginId(), "addPasskey", Result.SUCCESS, Map.of("name", n));
			return newId;
		});
		return new Passkey(id, n, c.algorithm(), c.backedUp(), now, null);
	}

	public void rename(User user, long passkeyId, String name, Actor actor) {
		String n = validName(name);
		db.inTransaction(tx -> {
			if (tx.update(PreparedSql.of("update user_passkeys set name = ? where passkey_id = ? and user_id = ?", n, passkeyId,
					user.userId())) != 1) {
				throw new NotFoundException("passkey " + passkeyId);
			}
			audit.record(tx, actor, null, "user", user.loginId(), "renamePasskey", Result.SUCCESS, Map.of("name", n));
			return null;
		});
	}

	/** Deletes one of the user's own passkeys (password required). */
	public void delete(User user, long passkeyId, char[] password, Actor actor) {
		try {
			requirePassword(user, password, actor);
			db.inTransaction(tx -> {
				if (tx.update(PreparedSql.of("delete from user_passkeys where passkey_id = ? and user_id = ?", passkeyId,
						user.userId())) != 1) {
					throw new NotFoundException("passkey " + passkeyId);
				}
				audit.record(tx, actor, null, "user", user.loginId(), "deletePasskey", Result.SUCCESS, Map.of("passkeyId", passkeyId));
				return null;
			});
		} finally {
			PasswordHasher.wipe(password);
		}
	}

	/** A system administrator removes all passkeys of a user (with the 2FA reset). */
	public void deleteAll(User user, Actor admin) {
		db.inTransaction(tx -> {
			int n = tx.update(PreparedSql.of("delete from user_passkeys where user_id = ?", user.userId()));
			if (n > 0) {
				audit.record(tx, admin, null, "user", user.loginId(), "resetPasskeys", Result.SUCCESS, Map.of("count", n));
			}
			return null;
		});
	}

	// ---- sign-in ----

	/** Options for sign-in without a login ID: any discoverable passkey of this RP. */
	public RequestOptions startLogin() {
		rp();
		return new RequestOptions(WebAuthn.newChallenge(), List.of());
	}

	/**
	 * Sign-in with a passkey alone (user verification required). Returns the user, or empty after recording
	 * the failure (same answer whatever the reason).
	 */
	public Optional<User> login(byte[] challenge, AssertionResponse a, String clientIp) {
		Instant now = clock.instant();
		Actor anonymous = new Actor(0, null, clientIp);
		if (policy.ipMaxFailures() > 0 && clientIp != null
				&& repo.countFailuresByIp(db, clientIp, now.minus(policy.ipWindow())) >= policy.ipMaxFailures()) {
			return loginFailed(anonymous, "", "too many failures from the client address", now);
		}
		Optional<Stored> stored = find(a.credentialId());
		if (stored.isEmpty()) {
			return loginFailed(anonymous, "", "unknown passkey", now);
		}
		Stored s = stored.get();
		User user = repo.findById(db, s.userId()).orElse(null);
		if (user == null) {
			return loginFailed(anonymous, "", "unknown passkey", now);
		}
		Actor actor = new Actor(user.userId(), user.loginId(), clientIp);
		if (a.userHandle() != null && a.userHandle().length > 0) {
			byte[] handle = db.queryOne(PreparedSql.of("select webauthn_user_id from users where user_id = ?", user.userId()),
					r -> r.getBytes(1)).orElse(null);
			if (handle == null || !Arrays.equals(handle, a.userHandle())) {
				return loginFailed(actor, user.loginId(), "user handle mismatch", now);
			}
		}
		WebAuthn.Assertion ok;
		try {
			ok = rp().verifyAssertion(a.clientDataJson(), a.authenticatorData(), a.signature(), challenge, s.publicKey(), s.signCount(),
					true);
		} catch (WebAuthnException e) {
			return loginFailed(actor, user.loginId(), "passkey: " + e.getMessage(), now);
		}
		if (user.isLocked(now)) {
			return loginFailed(actor, user.loginId(), "locked", now);
		}
		if (!user.enabled()) {
			return loginFailed(actor, user.loginId(), "disabled", now);
		}
		db.inTransaction(tx -> {
			used(tx, s, ok, now);
			repo.recordLoginSuccess(tx, user.userId(), now);
			repo.insertLoginAttempt(tx, user.loginId(), clientIp, true, now);
			audit.record(tx, actor, null, "session", user.loginId(), "loginPasskey", Result.SUCCESS, null);
			return null;
		});
		return repo.findById(db, user.userId());
	}

	/** Options for the second step: the user's own passkeys. */
	public RequestOptions startSecondFactor(long userId) {
		rp();
		return new RequestOptions(WebAuthn.newChallenge(), db.queryList(PreparedSql.of(
				"select credential_id from user_passkeys where user_id = ?", userId), r -> r.getBytes(1)));
	}

	/**
	 * Second step after the password. True when one of the user's passkeys signed the challenge; a failure
	 * counts as a failed login of the account (and locks it after the configured number of failures).
	 */
	public boolean verifySecondFactor(User user, byte[] challenge, AssertionResponse a, Actor actor) {
		Instant now = clock.instant();
		if (user.isLocked(now)) {
			audit(actor, user, "loginMfa", Result.FAILURE, "locked");
			return false;
		}
		Optional<Stored> stored = find(a.credentialId()).filter(s -> s.userId() == user.userId());
		String reason = null;
		WebAuthn.Assertion ok = null;
		if (stored.isEmpty()) {
			reason = "not a passkey of the user";
		} else {
			try {
				ok = rp().verifyAssertion(a.clientDataJson(), a.authenticatorData(), a.signature(), challenge, stored.get().publicKey(),
						stored.get().signCount(), false);
			} catch (WebAuthnException e) {
				reason = "passkey: " + e.getMessage();
			}
		}
		if (ok != null) {
			WebAuthn.Assertion verified = ok;
			db.inTransaction(tx -> {
				used(tx, stored.get(), verified, now);
				tx.update(PreparedSql.of("update users set failed_count = 0, locked_until = null, last_login_at = ? where user_id = ?",
						now, user.userId()));
				audit.record(tx, actor, null, "session", user.loginId(), "loginMfa", Result.SUCCESS, Map.of("method", "passkey"));
				return null;
			});
			return true;
		}
		String why = reason;
		db.inTransaction(tx -> {
			int failures = tx.queryOne(PreparedSql.of("select failed_count from users where user_id = ?", user.userId()),
					r -> r.getInt(1)).orElse(0) + 1;
			Instant lock = failures >= policy.maxFailures() ? now.plus(policy.lockDuration()) : null;
			tx.update(PreparedSql.of("update users set failed_count = ?, locked_until = ? where user_id = ?",
					lock == null ? failures : 0, lock == null ? BindValue.ofNull(Types.TIMESTAMP) : lock, user.userId()));
			audit.record(tx, actor, null, "session", user.loginId(), "loginMfa", Result.FAILURE,
					Map.of("method", "passkey", "reason", lock == null ? why : why + ", locked"));
			return null;
		});
		return false;
	}

	// ---- parts ----

	private WebAuthn rp() {
		if (webauthn == null) {
			throw new ConflictException("error.passkey.disabled");
		}
		return webauthn;
	}

	private Optional<Stored> find(byte[] credentialId) {
		if (credentialId == null || credentialId.length == 0 || credentialId.length > 1023) {
			return Optional.empty();
		}
		return db.queryOne(PreparedSql.of("select passkey_id, user_id, public_key, sign_count from user_passkeys where credential_id = ?",
				credentialId), r -> new Stored(r.getLong(1), r.getLong(2), r.getBytes(3), r.getLong(4)));
	}

	private static void used(org.tamacat.sql.SqlOperations tx, Stored s, WebAuthn.Assertion a, Instant now) {
		tx.update(PreparedSql.of("update user_passkeys set sign_count = ?, backed_up = ?, last_used_at = ? where passkey_id = ?",
				a.signCount(), a.backedUp(), now, s.passkeyId()));
	}

	private Optional<User> loginFailed(Actor actor, String loginId, String reason, Instant now) {
		db.inTransaction(tx -> {
			repo.insertLoginAttempt(tx, loginId, actor.clientIp(), false, now);
			audit.record(tx, actor, null, "session", loginId, "loginPasskey", Result.FAILURE, Map.of("reason", reason));
			return null;
		});
		return Optional.empty();
	}

	private void requirePassword(User user, char[] password, Actor actor) {
		String stored = repo.findPasswordHash(db, user.userId()).orElseThrow();
		if (password == null || !hasher.verify(password, stored)) {
			audit(actor, user, "passkey", Result.FAILURE, "wrong password");
			throw ValidationException.of("password", "error.password.current");
		}
	}

	private static String validName(String name) {
		String n = name == null ? "" : name.strip();
		if (n.isEmpty() || n.length() > MAX_NAME || n.chars().anyMatch(Character::isISOControl)) {
			throw ValidationException.of("name", "error.passkey.name");
		}
		return n;
	}

	private void audit(Actor actor, User user, String action, Result result, String reason) {
		db.inTransaction(tx -> {
			audit.record(tx, actor, null, "user", user.loginId(), action, result, Map.of("reason", reason));
			return null;
		});
	}
}
