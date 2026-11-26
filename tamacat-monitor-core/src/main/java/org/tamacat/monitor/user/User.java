package org.tamacat.monitor.user;

import java.time.Instant;
import java.util.Set;

/** A user of tamacat-monitor (without the password hash). */
public record User(
		long userId,
		String loginId,
		String displayName,
		String email,
		String locale,
		boolean enabled,
		boolean mustChangePassword,
		boolean mfaEnabled,
		int passkeyCount,
		Instant lockedUntil,
		Instant lastLoginAt,
		Set<Role> roles,
		Instant createdAt,
		Instant updatedAt) {

	public User {
		roles = Set.copyOf(roles);
	}

	public boolean has(Role role) {
		return roles.contains(role);
	}

	public boolean isSystemAdmin() {
		return roles.contains(Role.SYSTEM_ADMIN);
	}

	/** A second factor is due after the password: TOTP or a passkey. */
	public boolean hasSecondFactor() {
		return mfaEnabled || passkeyCount > 0;
	}

	public boolean isLocked(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	User withRoles(Set<Role> r) {
		return new User(userId, loginId, displayName, email, locale, enabled, mustChangePassword, mfaEnabled, passkeyCount,
				lockedUntil, lastLoginAt, r, createdAt, updatedAt);
	}
}
