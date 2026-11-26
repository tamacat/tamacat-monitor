package org.tamacat.monitor.web;

import java.io.IOException;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tamacat.dao.PreparedSql;

/**
 * {@code GET /health}: 200 {"status":"UP"} when the central DB answers, otherwise 503.
 * No details are exposed without authentication (pool statistics come later on an admin page).
 */
@WebServlet(urlPatterns = "/health")
public class HealthServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(HealthServlet.class);

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		boolean up;
		try {
			AppContext app = AppContext.of(getServletContext());
			up = app.centralDb().queryOne(PreparedSql.of("select 1"), r -> r.getInt(1)).orElse(0) == 1;
		} catch (RuntimeException e) {
			LOG.warn("health check failed: {}", e.toString());
			up = false;
		}
		JsonObject body = Json.createObjectBuilder().add("status", up ? "UP" : "DOWN").build();
		resp.setStatus(up ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-store");
		resp.getWriter().write(body.toString());
	}
}
