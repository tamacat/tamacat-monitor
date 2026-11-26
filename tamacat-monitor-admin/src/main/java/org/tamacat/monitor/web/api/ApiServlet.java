package org.tamacat.monitor.web.api;

import java.io.IOException;
import java.util.Arrays;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tamacat.dao.exception.InvalidParameterException;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.user.Role;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.web.AppContext;
import org.tamacat.monitor.web.WebSession;

/**
 * Base of the JSON API servlets: maps exceptions to status codes and never exposes internal
 * messages (stack traces and SQL stay in the server log).
 *
 * <pre>
 * 400 {"error":"error.validation","fields":{"name":"error.server.name"}}
 * 403 {"error":"error.forbidden"}
 * 404 {"error":"error.notFound"}
 * 409 {"error":"error.server.duplicate"}
 * 500 {"error":"error.internal"}
 * </pre>
 */
public abstract class ApiServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(ApiServlet.class);

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		try {
			super.service(req, res);
		} catch (ValidationException e) {
			ApiSupport.write(res, HttpServletResponse.SC_BAD_REQUEST, ApiSupport.fieldErrors(e.fieldErrors()));
		} catch (BadRequestException | InvalidParameterException e) {
			LOG.debug("bad request {}: {}", req.getRequestURI(), e.getMessage());
			ApiSupport.error(res, HttpServletResponse.SC_BAD_REQUEST, "error.badRequest");
		} catch (ForbiddenException | org.tamacat.monitor.common.ForbiddenException e) {
			ApiSupport.error(res, HttpServletResponse.SC_FORBIDDEN, "error.forbidden");
		} catch (NotFoundException e) {
			ApiSupport.error(res, HttpServletResponse.SC_NOT_FOUND, "error.notFound");
		} catch (ConflictException e) {
			ApiSupport.error(res, HttpServletResponse.SC_CONFLICT, e.messageKey());
		} catch (RuntimeException e) {
			LOG.error("API failure {} {}", req.getMethod(), req.getRequestURI(), e);
			if (!res.isCommitted()) {
				res.reset();
				ApiSupport.error(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "error.internal");
			}
		}
	}

	protected AppContext app() {
		return AppContext.of(getServletContext());
	}

	protected static User user(HttpServletRequest req) {
		User u = WebSession.currentUser(req);
		if (u == null) {
			throw new ForbiddenException();
		}
		return u;
	}

	protected static User requireRole(HttpServletRequest req, Role... anyOf) {
		User u = user(req);
		if (Arrays.stream(anyOf).noneMatch(u::has)) {
			throw new ForbiddenException();
		}
		return u;
	}

	/** Path segments after the servlet path: "/12/password" gives ["12", "password"]. */
	protected static String[] segments(HttpServletRequest req) {
		String info = req.getPathInfo();
		if (info == null || info.equals("/")) {
			return new String[0];
		}
		return info.substring(1).split("/");
	}

	protected static long id(String segment) {
		try {
			return Long.parseLong(segment);
		} catch (NumberFormatException e) {
			throw new NotFoundException("bad id");
		}
	}
}
