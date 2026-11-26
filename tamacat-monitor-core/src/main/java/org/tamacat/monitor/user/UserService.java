package org.tamacat.monitor.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tamacat.sql.Database;
import org.tamacat.monitor.audit.AuditLog;
import org.tamacat.monitor.audit.AuditLog.Result;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.security.PasswordHasher;

/**
 * User management and authentication against the central DB.
 */
public final class UserService {

	private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

	public static final Set<String> LOCALES = Set.of("en", "ja");
	private static final Pattern LOGIN_ID = Pattern.compile("[A-Za-z0-9._@-]{3,100}");
	private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,189}");
	public static final int PASSWORD_MIN = 12;
	public static final int PASSWORD_MAX = 128;

	/**
	 * Lockout policy: {@code maxFailures} consecutive failures lock the account for {@code lockDuration}.
	 * Independently, a client address with {@code ipMaxFailures} failed logins within {@code ipWindow} is
	 * refused (password spraying over many accounts, each below the account lockout); 0 disables it.
	 */
	public record LoginPolicy(int maxFailures, Duration lockDuration, int ipMaxFailures, Duration ipWindow) {
		public static final LoginPolicy DEFAULT = new LoginPolicy(5, Duration.ofMinutes(15));

		public LoginPolicy(int maxFailures, Duration lockDuration) {
			this(maxFailures, lockDuration, 20, Duration.ofMinutes(15));
		}
	}

	/** Input for create / update. Password is separate. */
	public record UserInput(String loginId, String displayName, String email, String locale, boolean enabled,
			Set<Role> roles) {
	}

	/** Result of {@link #authenticate}. The reason is for the audit log only; users see one generic message. */
	public sealed interface AuthResult {
		record Success(User user) implements AuthResult {
		}

		record Failure(String reason) implements AuthResult {
		}
	}

	private final Database db;
	private final UserRepository repo;
	private final PasswordHasher hasher;
	private final AuditLog audit;
	private final Clock clock;
	private final LoginPolicy policy;

	public UserService(Database db, PasswordHasher hasher, AuditLog audit, Clock clock, LoginPolicy policy) {
		this.db = db;
		this.repo = new UserRepository();
		this.hasher = hasher;
		this.audit = audit;
		this.clock = clock;
		this.policy = policy;
	}

	// ---- authentication ----

	public AuthResult authenticate(String loginId, char[] password, String clientIp) {
		Instant now = clock.instant();
		String id = loginId == null ? "" : loginId.strip();
		Actor actor = new Actor(0, id, clientIp);
		try {
			if (policy.ipMaxFailures() > 0 && clientIp != null
					&& repo.countFailuresByIp(db, clientIp, now.minus(policy.ipWindow())) >= policy.ipMaxFailures()) {
				hasher.dummyVerify(password);
				return fail(actor, id, "too many failures from the client address", now);
			}
			Optional<UserRepository.Credential> cred = id.isEmpty() ? Optional.empty() : repo.findCredential(db, id);
			if (cred.isEmpty()) {
				hasher.dummyVerify(password);
				return fail(actor, id, "unknown user", now);
			}
			UserRepository.Credential c = cred.get();
			actor = new Actor(c.userId(), id, clientIp);
			if (c.lockedUntil() != null && c.lockedUntil().isAfter(now)) {
				hasher.dummyVerify(password);
				return fail(actor, id, "locked", now);
			}
			if (!hasher.verify(password, c.passwordHash())) {
				int failures = c.failedCount() + 1;
				Instant lock = failures >= policy.maxFailures() ? now.plus(policy.lockDuration()) : null;
				repo.recordLoginFailure(db, c.userId(), lock == null ? failures : 0, lock);
				return fail(actor, id, lock == null ? "bad password" : "bad password, locked", now);
			}
			if (!c.enabled()) {
				return fail(actor, id, "disabled", now);
			}
			Actor a = actor;
			User user = db.inTransaction(tx -> {
				repo.recordLoginSuccess(tx, c.userId(), now);
				if (hasher.needsRehash(c.passwordHash())) {
					tx.update(org.tamacat.dao.PreparedSql.of("update users set password_hash = ? where user_id = ?",
							hasher.hash(password), c.userId()));
				}
				repo.insertLoginAttempt(tx, id, clientIp, true, now);
				audit.record(tx, a, null, "session", id, "login", Result.SUCCESS, null);
				return repo.findById(tx, c.userId()).orElseThrow();
			});
			return new AuthResult.Success(user);
		} finally {
			PasswordHasher.wipe(password);
		}
	}

	private AuthResult fail(Actor actor, String loginId, String reason, Instant now) {
		db.inTransaction(tx -> {
			repo.insertLoginAttempt(tx, loginId, actor.clientIp(), false, now);
			audit.record(tx, actor, null, "session", loginId, "login", Result.FAILURE, Map.of("reason", reason));
			return null;
		});
		return new AuthResult.Failure(reason);
	}

	// ---- queries ----

	public List<User> findAll() {
		return repo.findAll(db);
	}

	public Optional<User> findById(long userId) {
		return repo.findById(db, userId);
	}

	public User get(long userId) {
		return repo.findById(db, userId).orElseThrow(() -> new NotFoundException("user " + userId));
	}

	public Set<Long> serverIds(long userId) {
		return repo.findServerIds(db, userId);
	}

	public List<UserScope> scopes(long userId) {
		return repo.findScopes(db, userId);
	}

	// ---- changes ----

	public User create(UserInput in, char[] password, Actor actor) {
		try {
			validate(in, true);
			validatePassword(password, in.loginId(), "password");
			Instant now = clock.instant();
			String hash = hasher.hash(password);
			long id = db.inTransaction(tx -> {
				if (repo.findByLoginId(tx, in.loginId().strip()).isPresent()) {
					throw new ConflictException("error.user.duplicate");
				}
				long newId = repo.insert(tx, in.loginId().strip(), in.displayName().strip(), in.email(),
						in.locale(), in.enabled(), hash, true, now);
				repo.setRoles(tx, newId, in.roles());
				audit.record(tx, actor, null, "user", in.loginId(), "create", Result.SUCCESS,
						Map.of("roles", in.roles().toString(), "enabled", in.enabled()));
				return newId;
			});
			return get(id);
		} finally {
			PasswordHasher.wipe(password);
		}
	}

	public User update(long userId, UserInput in, Actor actor) {
		validate(in, false);
		Instant now = clock.instant();
		db.inTransaction(tx -> {
			User before = repo.findById(tx, userId).orElseThrow(() -> new NotFoundException("user " + userId));
			boolean losesAdmin = before.isSystemAdmin() && before.enabled()
					&& (!in.roles().contains(Role.SYSTEM_ADMIN) || !in.enabled());
			if (losesAdmin && repo.countEnabledSystemAdmins(tx) <= 1) {
				throw new ConflictException("error.user.lastAdmin");
			}
			if (userId == actor.userId() && losesAdmin) {
				throw new ConflictException("error.user.selfAdmin");
			}
			repo.update(tx, userId, in.displayName().strip(), in.email(), in.locale(), in.enabled(), now);
			repo.setRoles(tx, userId, in.roles());
			if (!in.roles().contains(Role.SERVER_ADMIN)) {
				repo.setServerIds(tx, userId, Set.of());
			}
			audit.record(tx, actor, null, "user", before.loginId(), "update", Result.SUCCESS,
					Map.of("roles", in.roles().toString(), "enabled", in.enabled()));
			return null;
		});
		return get(userId);
	}

	public void delete(long userId, Actor actor) {
		if (userId == actor.userId()) {
			throw new ConflictException("error.user.selfDelete");
		}
		db.inTransaction(tx -> {
			User before = repo.findById(tx, userId).orElseThrow(() -> new NotFoundException("user " + userId));
			if (before.isSystemAdmin() && before.enabled() && repo.countEnabledSystemAdmins(tx) <= 1) {
				throw new ConflictException("error.user.lastAdmin");
			}
			repo.delete(tx, userId);
			audit.record(tx, actor, null, "user", before.loginId(), "delete", Result.SUCCESS, null);
			return null;
		});
	}

	/** Administrator sets a new password; the user must change it at next login. Also unlocks. */
	public void resetPassword(long userId, char[] password, Actor actor) {
		try {
			User u = get(userId);
			validatePassword(password, u.loginId(), "password");
			String hash = hasher.hash(password);
			db.inTransaction(tx -> {
				repo.updatePassword(tx, userId, hash, true, clock.instant());
				audit.record(tx, actor, null, "user", u.loginId(), "resetPassword", Result.SUCCESS, null);
				return null;
			});
		} finally {
			PasswordHasher.wipe(password);
		}
	}

	public void changeOwnPassword(long userId, char[] current, char[] next, Actor actor) {
		try {
			User u = get(userId);
			String stored = repo.findPasswordHash(db, userId).orElseThrow();
			if (!hasher.verify(current, stored)) {
				db.inTransaction(tx -> {
					audit.record(tx, actor, null, "user", u.loginId(), "changePassword", Result.FAILURE,
							Map.of("reason", "wrong current password"));
					return null;
				});
				throw ValidationException.of("currentPassword", "error.password.current");
			}
			validatePassword(next, u.loginId(), "newPassword");
			if (hasher.verify(next, stored)) {
				throw ValidationException.of("newPassword", "error.password.same");
			}
			String hash = hasher.hash(next);
			db.inTransaction(tx -> {
				repo.updatePassword(tx, userId, hash, false, clock.instant());
				audit.record(tx, actor, null, "user", u.loginId(), "changePassword", Result.SUCCESS, null);
				return null;
			});
		} finally {
			PasswordHasher.wipe(current);
			PasswordHasher.wipe(next);
		}
	}

	public void unlock(long userId, Actor actor) {
		User u = get(userId);
		db.inTransaction(tx -> {
			repo.unlock(tx, userId, clock.instant());
			audit.record(tx, actor, null, "user", u.loginId(), "unlock", Result.SUCCESS, null);
			return null;
		});
	}

	public void setLocale(long userId, String locale) {
		if (!LOCALES.contains(locale)) {
			throw ValidationException.of("locale", "error.locale");
		}
		repo.updateLocale(db, userId, locale, clock.instant());
	}

	/** Servers managed by a SERVER_ADMIN, and host groups permitted to an OPERATOR / VIEWER. */
	public void setAssignments(long userId, Set<Long> serverIds, List<UserScope> scopes, Actor actor) {
		db.inTransaction(tx -> {
			User u = repo.findById(tx, userId).orElseThrow(() -> new NotFoundException("user " + userId));
			repo.setServerIds(tx, userId, u.has(Role.SERVER_ADMIN) ? serverIds : Set.of());
			repo.setScopes(tx, userId, scopes, clock.instant());
			audit.record(tx, actor, null, "user", u.loginId(), "assign", Result.SUCCESS,
					Map.of("servers", serverIds.toString(), "scopes", scopes.size()));
			return null;
		});
	}

	/**
	 * Creates the first administrator when the user table is empty.
	 * The password must be changed at first login.
	 *
	 * @return true if the administrator was created
	 */
	public boolean bootstrapAdmin(String loginId, char[] password) {
		try {
			if (repo.countUsers(db) > 0) {
				return false;
			}
			validatePassword(password, loginId, "password");
			String hash = hasher.hash(password);
			Instant now = clock.instant();
			db.inTransaction(tx -> {
				long id = repo.insert(tx, loginId, "Administrator", null, "en", true, hash, true, now);
				repo.setRoles(tx, id, EnumSet.of(Role.SYSTEM_ADMIN));
				audit.record(tx, Actor.system(), null, "user", loginId, "bootstrap", Result.SUCCESS, null);
				return null;
			});
			LOG.warn("Initial administrator '{}' created; the password must be changed at first login.", loginId);
			return true;
		} finally {
			PasswordHasher.wipe(password);
		}
	}

	// ---- validation ----

	private static void validate(UserInput in, boolean creating) {
		ValidationException.Collector v = new ValidationException.Collector();
		if (creating) {
			v.check(in.loginId() != null && LOGIN_ID.matcher(in.loginId().strip()).matches(), "loginId", "error.loginId");
		}
		v.check(in.displayName() != null && !in.displayName().isBlank() && in.displayName().strip().length() <= 200,
				"displayName", "error.displayName");
		v.check(in.email() == null || in.email().isBlank() || (in.email().length() <= 254 && EMAIL.matcher(in.email()).matches()),
				"email", "error.email");
		v.check(in.locale() != null && LOCALES.contains(in.locale()), "locale", "error.locale");
		v.check(in.roles() != null && !in.roles().isEmpty(), "roles", "error.roles");
		v.throwIfAny();
	}

	static void validatePassword(char[] password, String loginId, String field) {
		if (password == null || password.length < PASSWORD_MIN || password.length > PASSWORD_MAX) {
			throw ValidationException.of(field, "error.password.length");
		}
		String lower = new String(password).toLowerCase(Locale.ROOT);
		if (loginId != null && !loginId.isBlank() && lower.contains(loginId.strip().toLowerCase(Locale.ROOT))) {
			throw ValidationException.of(field, "error.password.containsLoginId");
		}
		boolean allSame = true;
		for (char c : password) {
			if (c != password[0]) {
				allSame = false;
				break;
			}
		}
		if (allSame) {
			throw ValidationException.of(field, "error.password.weak");
		}
	}
}
