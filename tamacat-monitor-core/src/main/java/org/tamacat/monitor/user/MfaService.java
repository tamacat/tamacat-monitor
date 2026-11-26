package org.tamacat.monitor.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

import org.tamacat.dao.BindValue;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Database;
import org.tamacat.sql.SqlOperations;
import org.tamacat.monitor.audit.AuditLog;
import org.tamacat.monitor.audit.AuditLog.Result;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.security.PasswordHasher;
import org.tamacat.monitor.security.SecretCipher;
import org.tamacat.monitor.security.Totp;
import org.tamacat.monitor.user.UserService.LoginPolicy;

/**
 * Two-factor authentication with TOTP (T-602).
 * <ul>
 * <li>Set-up: a new secret is shown (QR code / key); it is stored only after the user enters a valid code
 * and the password. Ten one-time recovery codes are returned once; only their SHA-256 is stored.</li>
 * <li>Login: after the password, a TOTP code (each accepted once, +-1 step) or a recovery code. Wrong codes
 * count as login failures of the account (lockout).</li>
 * <li>Disable: password and a code. A system administrator can reset it for a user who lost the device.</li>
 * </ul>
 * The secret is encrypted with {@link SecretCipher} (purpose {@code mfa:<userId>}).
 */
public final class MfaService {

	public static final int RECOVERY_CODES = 10;
	private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"; // no 0/o, 1/l/i
	private static final SecureRandom RANDOM = new SecureRandom();

	public record Setup(String secret, String uri) {
	}

	private final Database db;
	private final SecretCipher cipher;
	private final PasswordHasher hasher;
	private final AuditLog audit;
	private final Clock clock;
	private final LoginPolicy policy;
	private final String issuer;

	public MfaService(Database db, SecretCipher cipher, PasswordHasher hasher, AuditLog audit, Clock clock, LoginPolicy policy,
			String issuer) {
		this.db = db;
		this.cipher = cipher;
		this.hasher = hasher;
		this.audit = audit;
		this.clock = clock;
		this.policy = policy;
		this.issuer = issuer;
	}

	/** A new secret for set-up (not stored yet). */
	public Setup newSetup(User user) {
		byte[] secret = Totp.newSecret();
		return new Setup(Totp.base32(secret), Totp.uri(issuer, user.loginId(), secret));
	}

	/** Enables 2FA with the secret of {@link #newSetup}; returns the recovery codes (shown once). */
	public List<String> enable(User user, String secretBase32, char[] password, String code, Actor actor) {
		try {
			if (user.mfaEnabled()) {
				throw new ConflictException("error.mfa.alreadyEnabled");
			}
			requirePassword(user, password, actor, "enableMfa");
			byte[] secret = Totp.fromBase32(secretBase32);
			Instant now = clock.instant();
			OptionalLong step = Totp.verify(secret, code, now, 1, 0);
			if (step.isEmpty()) {
				throw ValidationException.of("code", "error.mfa.code");
			}
			List<String> codes = newRecoveryCodes();
			db.inTransaction(tx -> {
				tx.update(PreparedSql.of("update users set mfa_enabled = true, mfa_secret = ?, mfa_last_step = ?, updated_at = ? "
						+ "where user_id = ?", cipher.encrypt(secretBase32, purpose(user.userId())), step.getAsLong(), now,
						user.userId()));
				storeRecoveryCodes(tx, user.userId(), codes);
				audit.record(tx, actor, null, "user", user.loginId(), "enableMfa", Result.SUCCESS, null);
				return null;
			});
			return codes;
		} finally {
			PasswordHasher.wipe(password);
		}
	}

	/** Disables 2FA of the user's own account: password and a TOTP or recovery code. */
	public void disable(User user, char[] password, String code, Actor actor) {
		try {
			if (!user.mfaEnabled()) {
				throw new ConflictException("error.mfa.notEnabled");
			}
			requirePassword(user, password, actor, "disableMfa");
			if (!check(user.userId(), code)) {
				throw ValidationException.of("code", "error.mfa.code");
			}
			clear(user, actor, "disableMfa");
		} finally {
			PasswordHasher.wipe(password);
		}
	}

	/** A system administrator turns off 2FA of a user (lost device); the user can set it up again. */
	public void reset(User user, Actor admin) {
		clear(user, admin, "resetMfa");
	}

	/**
	 * Second login step. True when the code is valid; a wrong code counts as a failed login of the account
	 * (and locks it after the configured number of failures).
	 */
	public boolean verifyLogin(User user, String code, Actor actor) {
		Instant now = clock.instant();
		if (user.isLocked(now)) {
			audit(actor, user, Result.FAILURE, "locked");
			return false;
		}
		if (check(user.userId(), code)) {
			db.inTransaction(tx -> {
				tx.update(PreparedSql.of("update users set failed_count = 0, locked_until = null, last_login_at = ? where user_id = ?",
						now, user.userId()));
				audit.record(tx, actor, null, "session", user.loginId(), "loginMfa", Result.SUCCESS, null);
				return null;
			});
			return true;
		}
		db.inTransaction(tx -> {
			int failures = tx.queryOne(PreparedSql.of("select failed_count from users where user_id = ?", user.userId()),
					r -> r.getInt(1)).orElse(0) + 1;
			Instant lock = failures >= policy.maxFailures() ? now.plus(policy.lockDuration()) : null;
			tx.update(PreparedSql.of("update users set failed_count = ?, locked_until = ? where user_id = ?",
					lock == null ? failures : 0, lock == null ? BindValue.ofNull(Types.TIMESTAMP) : lock, user.userId()));
			audit.record(tx, actor, null, "session", user.loginId(), "loginMfa", Result.FAILURE,
					Map.of("reason", lock == null ? "wrong code" : "wrong code, locked"));
			return null;
		});
		return false;
	}

	public int remainingRecoveryCodes(long userId) {
		return db.queryOne(PreparedSql.of("select count(*) from user_recovery_codes where user_id = ? and used_at is null", userId),
				r -> r.getInt(1)).orElse(0);
	}

	// ---- parts ----

	/** TOTP code (6 digits; each step once) or an unused recovery code; consumes what matched. */
	private boolean check(long userId, String code) {
		if (code == null || code.isBlank()) {
			return false;
		}
		String c = code.strip();
		return db.inTransaction(tx -> {
			var row = tx.queryOne(PreparedSql.of("select mfa_secret, mfa_last_step from users where user_id = ? and mfa_enabled = true "
					+ "for update", userId), r -> new Object[] { r.getBytes(1), r.getLong(2) });
			if (row.isEmpty()) {
				return false;
			}
			if (c.replace(" ", "").matches("[0-9]{6}")) {
				byte[] secret = Totp.fromBase32(cipher.decrypt((byte[]) row.get()[0], purpose(userId)));
				OptionalLong step = Totp.verify(secret, c, clock.instant(), 1, (Long) row.get()[1]);
				if (step.isEmpty()) {
					return false;
				}
				tx.update(PreparedSql.of("update users set mfa_last_step = ? where user_id = ?", step.getAsLong(), userId));
				return true;
			}
			String hash = sha256(normalize(c));
			return tx.update(PreparedSql.of("update user_recovery_codes set used_at = ? where user_id = ? and code_hash = ? "
					+ "and used_at is null", clock.instant(), userId, hash)) == 1;
		});
	}

	private void clear(User user, Actor actor, String action) {
		db.inTransaction(tx -> {
			tx.update(PreparedSql.of("update users set mfa_enabled = false, mfa_secret = null, mfa_last_step = 0, updated_at = ? "
					+ "where user_id = ?", clock.instant(), user.userId()));
			tx.update(PreparedSql.of("delete from user_recovery_codes where user_id = ?", user.userId()));
			audit.record(tx, actor, null, "user", user.loginId(), action, Result.SUCCESS, null);
			return null;
		});
	}

	private void requirePassword(User user, char[] password, Actor actor, String action) {
		String stored = db.queryOne(PreparedSql.of("select password_hash from users where user_id = ?", user.userId()),
				r -> r.getString(1)).orElseThrow();
		if (password == null || !hasher.verify(password, stored)) {
			audit(actor, user, Result.FAILURE, action + ": wrong password");
			throw ValidationException.of("password", "error.password.current");
		}
	}

	private void audit(Actor actor, User user, Result result, String reason) {
		db.inTransaction(tx -> {
			audit.record(tx, actor, null, "user", user.loginId(), "mfa", result, Map.of("reason", reason));
			return null;
		});
	}

	private static void storeRecoveryCodes(SqlOperations tx, long userId, List<String> codes) {
		tx.update(PreparedSql.of("delete from user_recovery_codes where user_id = ?", userId));
		for (String c : codes) {
			tx.update(PreparedSql.of("insert into user_recovery_codes (user_id, code_hash) values (?, ?)", userId, sha256(normalize(c))));
		}
	}

	/** 10 characters from a 31-letter alphabet (about 50 bits), shown as xxxxx-xxxxx. */
	static List<String> newRecoveryCodes() {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < RECOVERY_CODES; i++) {
			StringBuilder sb = new StringBuilder();
			for (int j = 0; j < 10; j++) {
				if (j == 5) {
					sb.append('-');
				}
				sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
			}
			out.add(sb.toString());
		}
		return out;
	}

	private static String normalize(String code) {
		return code.replace("-", "").replace(" ", "").toLowerCase(Locale.ROOT);
	}

	private static String sha256(String s) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String purpose(long userId) {
		return "mfa:" + userId;
	}
}
