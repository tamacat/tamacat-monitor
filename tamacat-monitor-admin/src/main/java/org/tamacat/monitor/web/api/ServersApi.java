package org.tamacat.monitor.web.api;

import java.io.IOException;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.tamacat.dao.Page;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.user.Role;
import org.tamacat.monitor.web.WebSession;
import org.tamacat.monitor.zabbix.ZabbixServerService;
import org.tamacat.monitor.zabbix.ZabbixServerService.ServerInput;
import org.tamacat.monitor.zabbix.ZabbixServerService.TestResult;

/**
 * Zabbix server registration (SYSTEM_ADMIN only). Passwords are write-only: they are never returned.
 * <pre>
 * GET    /api/servers
 * POST   /api/servers                  {name, description, dbUrl, readUser, readPassword, writeUser, writePassword, readOnly, enabled}
 * POST   /api/servers/test             {dbUrl, readUser, readPassword, serverId?}  (unsaved settings)
 * GET    /api/servers/{id}
 * PUT    /api/servers/{id}             same as POST; empty passwords keep the stored ones; clearWritePassword
 * DELETE /api/servers/{id}
 * POST   /api/servers/{id}/check       connect with the stored settings and store version / status
 * GET    /api/servers/{id}/hostgroups?q=&amp;limit=
 * </pre>
 */
@WebServlet(urlPatterns = { "/api/servers", "/api/servers/*" })
public class ServersApi extends ApiServlet {

	private static final long serialVersionUID = 1L;

	private ZabbixServerService servers() {
		return app().servers();
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
		requireRole(req, Role.SYSTEM_ADMIN);
		String[] seg = segments(req);
		if (seg.length == 0) {
			ApiSupport.write(res, 200, ApiSupport.array(servers().findAll(), ApiSupport::server));
		} else if (seg.length == 1) {
			ApiSupport.write(res, 200, ApiSupport.server(servers().get(id(seg[0]))).build());
		} else if (seg.length == 2 && seg[1].equals("hostgroups")) {
			int limit = parseLimit(req.getParameter("limit"));
			ApiSupport.write(res, 200, ApiSupport.array(
					servers().hostGroups(id(seg[0]), req.getParameter("q"), Page.first(limit)), ApiSupport::hostGroup));
		} else {
			throw new NotFoundException(req.getRequestURI());
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
		requireRole(req, Role.SYSTEM_ADMIN);
		String[] seg = segments(req);
		if (seg.length == 0) {
			ApiSupport.write(res, 201,
					ApiSupport.server(servers().create(input(ApiSupport.readObject(req)), WebSession.actor(req))).build());
		} else if (seg.length == 1 && seg[0].equals("test")) {
			JsonObject b = ApiSupport.readObject(req);
			TestResult r = b.containsKey("serverId") && !b.isNull("serverId")
					? servers().testSaved(ApiSupport.longValue(b, "serverId"), ApiSupport.str(b, "dbUrl"),
							ApiSupport.str(b, "readUser"), ApiSupport.chars(b, "readPassword"))
					: servers().test(ApiSupport.str(b, "dbUrl"), ApiSupport.str(b, "readUser"),
							ApiSupport.chars(b, "readPassword"));
			ApiSupport.write(res, 200, testResult(r));
		} else if (seg.length == 2 && seg[1].equals("check")) {
			ApiSupport.write(res, 200, ApiSupport.server(servers().check(id(seg[0]))).build());
		} else {
			throw new NotFoundException(req.getRequestURI());
		}
	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse res) throws IOException {
		requireRole(req, Role.SYSTEM_ADMIN);
		String[] seg = segments(req);
		if (seg.length != 1) {
			throw new NotFoundException(req.getRequestURI());
		}
		ApiSupport.write(res, 200, ApiSupport.server(
				servers().update(id(seg[0]), input(ApiSupport.readObject(req)), WebSession.actor(req))).build());
	}

	@Override
	protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws IOException {
		requireRole(req, Role.SYSTEM_ADMIN);
		String[] seg = segments(req);
		if (seg.length != 1) {
			throw new NotFoundException(req.getRequestURI());
		}
		servers().delete(id(seg[0]), WebSession.actor(req));
		ApiSupport.write(res, 200, Json.createObjectBuilder().add("ok", true).build());
	}

	private static ServerInput input(JsonObject b) {
		return new ServerInput(ApiSupport.str(b, "name"), ApiSupport.str(b, "description"), ApiSupport.str(b, "dbUrl"),
				ApiSupport.str(b, "readUser"), ApiSupport.chars(b, "readPassword"), ApiSupport.str(b, "writeUser"),
				ApiSupport.chars(b, "writePassword"), ApiSupport.bool(b, "clearWritePassword", false),
				ApiSupport.bool(b, "readOnly", true), ApiSupport.bool(b, "enabled", true));
	}

	private static JsonObject testResult(TestResult r) {
		JsonObjectBuilder b = Json.createObjectBuilder().add("connected", r.connected()).add("supported", r.supported());
		if (r.versionLabel() != null) {
			b.add("zabbixVersion", r.versionLabel());
		}
		if (r.messageKey() != null) {
			b.add("messageKey", r.messageKey());
		}
		if (r.detail() != null) {
			b.add("detail", r.detail());
		}
		return b.build();
	}

	private static int parseLimit(String s) {
		try {
			return s == null ? 200 : Math.clamp(Integer.parseInt(s), 1, 1000);
		} catch (NumberFormatException e) {
			return 200;
		}
	}
}
