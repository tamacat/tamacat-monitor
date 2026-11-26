package org.tamacat.monitor.web.filter;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.tamacat.monitor.user.User;
import org.tamacat.monitor.web.AppContext;
import org.tamacat.monitor.web.WebSession;
import org.tamacat.monitor.web.api.ApiSupport;

/**
 * Authentication. Every request except the public paths needs a logged-in, enabled, unlocked user.
 * The user is reloaded from the central DB on each request and stored as a request attribute.
 * A user who must change the password can only reach the account page and its APIs.
 */
public class AuthFilter extends HttpFilter {

	private static final long serialVersionUID = 1L;

	private static final Set<String> PUBLIC = Set.of("/login", "/api/login", "/api/login/mfa", "/api/login/mfa/passkey/options",
			"/api/login/mfa/passkey", "/api/login/passkey/options", "/api/login/passkey", "/health", "/favicon.ico");
	private static final Set<String> PASSWORD_CHANGE_ALLOWED = Set.of("/account", "/api/me", "/api/me/password",
			"/api/me/locale", "/api/logout");

	@Override
	protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
			throws IOException, ServletException {
		String path = path(req);
		if (PUBLIC.contains(path) || path.startsWith("/static/")) {
			chain.doFilter(req, res);
			return;
		}
		Long userId = WebSession.userId(req.getSession(false));
		User user = null;
		if (userId != null) {
			AppContext app = AppContext.of(getServletContext());
			user = app.users().findById(userId)
					.filter(u -> u.enabled() && !u.isLocked(app.clock().instant()))
					.orElse(null);
			if (user == null) {
				WebSession.logout(req);
			}
		}
		if (user == null) {
			deny(req, res, path, HttpServletResponse.SC_UNAUTHORIZED, "error.unauthenticated", "/login");
			return;
		}
		req.setAttribute(WebSession.CURRENT_USER, user);
		if (user.mustChangePassword() && !PASSWORD_CHANGE_ALLOWED.contains(path)) {
			deny(req, res, path, HttpServletResponse.SC_FORBIDDEN, "error.passwordChangeRequired", "/account");
			return;
		}
		chain.doFilter(req, res);
	}

	static String path(HttpServletRequest req) {
		return req.getRequestURI().substring(req.getContextPath().length());
	}

	private static void deny(HttpServletRequest req, HttpServletResponse res, String path, int status, String key,
			String redirect) throws IOException {
		if (path.startsWith("/api/")) {
			ApiSupport.error(res, status, key);
		} else {
			res.sendRedirect(req.getContextPath() + redirect);
		}
	}
}
