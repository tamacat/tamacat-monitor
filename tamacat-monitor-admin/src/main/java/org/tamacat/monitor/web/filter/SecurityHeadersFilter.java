package org.tamacat.monitor.web.filter;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Security response headers for every response. Registered first in web.xml.
 * <p>
 * CSP allows scripts and styles from this origin only; pages pass data to scripts through a
 * {@code <script type="application/json">} element, so no inline script is needed.
 */
public class SecurityHeadersFilter extends HttpFilter {

	private static final long serialVersionUID = 1L;

	static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
			+ "font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; "
			+ "form-action 'self'; frame-ancestors 'none'";

	@Override
	protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
			throws IOException, ServletException {
		res.setHeader("Content-Security-Policy", CSP);
		res.setHeader("X-Content-Type-Options", "nosniff");
		res.setHeader("X-Frame-Options", "DENY");
		res.setHeader("Referrer-Policy", "no-referrer");
		res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
		res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
		if (req.isSecure()) {
			res.setHeader("Strict-Transport-Security", "max-age=31536000");
		}
		String path = req.getRequestURI().substring(req.getContextPath().length());
		if (!path.startsWith("/static/")) {
			res.setHeader("Cache-Control", "no-store");
		}
		chain.doFilter(req, res);
	}
}
