package org.tamacat.monitor.user;

/** Who performs an operation (for authorization checks and the audit log). */
public record Actor(long userId, String loginId, String clientIp) {

	/** The system itself (startup tasks). */
	public static Actor system() {
		return new Actor(0, "system", null);
	}
}
