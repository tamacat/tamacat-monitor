package org.tamacat.monitor.web;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.tamacat.monitor.user.Actor;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.user.UserService;

/**
 * Session attributes: the logged-in user id and the CSRF token. The user record itself is
 * reloaded from the central DB on each request (see AuthFilter), so role changes, disabling
 * and locking take effect immediately.
 */
public final class WebSession {

	static final String USER_ID = "tmon.userId";
	static final String CSRF = "tmon.csrf";
	/** Request attribute set by AuthFilter. */
	public static final String CURRENT_USER = "tmon.currentUser";

	private static final SecureRandom RANDOM = new SecureRandom();

	private WebSession() {
	}

	/** The user loaded by AuthFilter for this request, or null. */
	public static User currentUser(HttpServletRequest req) {
		return req.getAttribute(CURRENT_USER) instanceof User u ? u : null;
	}

	public static Long userId(HttpSession session) {
		return session == null ? null : session.getAttribute(USER_ID) instanceof Long id ? id : null;
	}

	/** Starts an authenticated session: new session id (fixation protection) and new CSRF token. */
	public static void login(HttpServletRequest req, long userId) {
		HttpSession s = req.getSession(true);
		req.changeSessionId();
		clearMfa(req);
		s.setAttribute(USER_ID, userId);
		s.setAttribute(CSRF, newToken());
	}

	// ---- second login step (T-602): the password was right, the TOTP / recovery code is still due ----
	static final String MFA_PENDING = "tmon.mfaPending";
	static final String MFA_PENDING_SINCE = "tmon.mfaPendingSince";
	static final String MFA_ATTEMPTS = "tmon.mfaAttempts";
	static final String MFA_SETUP = "tmon.mfaSetup";
	/** Time to enter the code after the password. */
	static final long MFA_PENDING_SECONDS = 300;
	/** Wrong codes per password entry; then the password is asked again. */
	static final int MFA_MAX_ATTEMPTS = 5;

	/** Remembers the user whose password was right; the session stays unauthenticated. */
	public static void startMfa(HttpServletRequest req, long userId, long nowEpochSecond) {
		HttpSession s = req.getSession(true);
		s.removeAttribute(USER_ID);
		s.setAttribute(MFA_PENDING, userId);
		s.setAttribute(MFA_PENDING_SINCE, nowEpochSecond);
		s.setAttribute(MFA_ATTEMPTS, 0);
	}

	/** The user waiting for the second step, or null (none, expired or too many attempts). */
	public static Long pendingMfa(HttpServletRequest req, long nowEpochSecond) {
		HttpSession s = req.getSession(false);
		if (s == null || !(s.getAttribute(MFA_PENDING) instanceof Long id) || !(s.getAttribute(MFA_PENDING_SINCE) instanceof Long since)
				|| !(s.getAttribute(MFA_ATTEMPTS) instanceof Integer attempts)) {
			return null;
		}
		if (nowEpochSecond - since > MFA_PENDING_SECONDS || attempts >= MFA_MAX_ATTEMPTS) {
			clearMfa(req);
			return null;
		}
		return id;
	}

	/** Counts a wrong code; after the maximum the password is asked again. */
	public static void mfaFailed(HttpServletRequest req) {
		HttpSession s = req.getSession(false);
		if (s != null && s.getAttribute(MFA_ATTEMPTS) instanceof Integer a) {
			s.setAttribute(MFA_ATTEMPTS, a + 1);
		}
	}

	public static void clearMfa(HttpServletRequest req) {
		HttpSession s = req.getSession(false);
		if (s != null) {
			s.removeAttribute(MFA_PENDING);
			s.removeAttribute(MFA_PENDING_SINCE);
			s.removeAttribute(MFA_ATTEMPTS);
		}
	}

	/** The secret being set up on the account page (kept in the session until confirmed with a code). */
	public static void setMfaSetup(HttpServletRequest req, String secret) {
		req.getSession(true).setAttribute(MFA_SETUP, secret);
	}

	public static String mfaSetup(HttpServletRequest req) {
		HttpSession s = req.getSession(false);
		return s != null && s.getAttribute(MFA_SETUP) instanceof String v ? v : null;
	}

	public static void clearMfaSetup(HttpServletRequest req) {
		HttpSession s = req.getSession(false);
		if (s != null) {
			s.removeAttribute(MFA_SETUP);
		}
	}

	// ---- WebAuthn challenges (T-801): one per purpose, used once, valid for 5 minutes ----
	static final long CHALLENGE_SECONDS = 300;

	/** Purposes of a WebAuthn challenge. */
	public enum Ceremony {
		REGISTER, LOGIN, SECOND_FACTOR
	}

	private record Challenge(byte[] value, long since) implements java.io.Serializable {
	}

	public static void setChallenge(HttpServletRequest req, Ceremony ceremony, byte[] challenge, long nowEpochSecond) {
		req.getSession(true).setAttribute("tmon.webauthn." + ceremony, new Challenge(challenge.clone(), nowEpochSecond));
	}

	/** The challenge of the ceremony, removed from the session (null when none or expired). */
	public static byte[] takeChallenge(HttpServletRequest req, Ceremony ceremony, long nowEpochSecond) {
		HttpSession s = req.getSession(false);
		if (s == null) {
			return null;
		}
		Object v = s.getAttribute("tmon.webauthn." + ceremony);
		s.removeAttribute("tmon.webauthn." + ceremony);
		return v instanceof Challenge c && nowEpochSecond - c.since() <= CHALLENGE_SECONDS ? c.value() : null;
	}

	public static void logout(HttpServletRequest req) {
		HttpSession s = req.getSession(false);
		if (s != null) {
			s.invalidate();
		}
	}

	/** The CSRF token of the session, created when missing (also for the anonymous login page). */
	public static String csrfToken(HttpServletRequest req) {
		HttpSession s = req.getSession(true);
		if (!(s.getAttribute(CSRF) instanceof String t)) {
			String token = newToken();
			s.setAttribute(CSRF, token);
			return token;
		}
		return t;
	}

	public static boolean csrfMatches(HttpServletRequest req, String presented) {
		HttpSession s = req.getSession(false);
		if (s == null || presented == null || !(s.getAttribute(CSRF) instanceof String expected)) {
			return false;
		}
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
				presented.getBytes(StandardCharsets.US_ASCII));
	}

	private static String newToken() {
		byte[] b = new byte[32];
		RANDOM.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	/**
	 * Client address. X-Forwarded-For is trusted only from a loopback peer (a reverse proxy on the same
	 * host), and only its last entry: proxies append the address they saw, so earlier entries come from
	 * the client and could be forged (audit log, login throttling).
	 */
	public static String clientIp(HttpServletRequest req) {
		String remote = req.getRemoteAddr();
		if (remote != null && (remote.equals("127.0.0.1") || remote.equals("::1") || remote.equals("0:0:0:0:0:0:0:1"))) {
			String xff = req.getHeader("X-Forwarded-For");
			if (xff != null && !xff.isBlank()) {
				String[] hops = xff.split(",");
				String last = hops[hops.length - 1].strip();
				if (last.length() <= 45 && last.matches("[0-9A-Fa-f:.]+")) {
					return last;
				}
			}
		}
		return remote;
	}

	public static Actor actor(HttpServletRequest req) {
		User u = currentUser(req);
		return u == null ? new Actor(0, null, clientIp(req)) : new Actor(u.userId(), u.loginId(), clientIp(req));
	}

	/** User preference, then Accept-Language, then English. */
	public static String locale(HttpServletRequest req) {
		User u = currentUser(req);
		if (u != null && UserService.LOCALES.contains(u.locale())) {
			return u.locale();
		}
		Locale l = req.getLocale();
		return l != null && l.getLanguage().equals("ja") ? "ja" : "en";
	}
}
