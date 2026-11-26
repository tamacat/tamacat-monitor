package org.tamacat.monitor.web.filter;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tamacat.monitor.web.WebSession;
import org.tamacat.monitor.web.api.ApiSupport;

/**
 * CSRF protection (synchronizer token): every state-changing request must send the session's
 * token in the {@code X-CSRF-Token} header. This includes the login request (login CSRF);
 * the login page gets a token in its anonymous session. The session cookie is also
 * SameSite=Strict, so this is a second line of defense.
 */
public class CsrfFilter extends HttpFilter {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(CsrfFilter.class);
	private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
	public static final String HEADER = "X-CSRF-Token";

	@Override
	protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
			throws IOException, ServletException {
		if (!SAFE.contains(req.getMethod()) && !WebSession.csrfMatches(req, req.getHeader(HEADER))) {
			LOG.warn("CSRF token mismatch: {} {} from {}", req.getMethod(), req.getRequestURI(), WebSession.clientIp(req));
			ApiSupport.error(res, HttpServletResponse.SC_FORBIDDEN, "error.csrf");
			return;
		}
		chain.doFilter(req, res);
	}
}
