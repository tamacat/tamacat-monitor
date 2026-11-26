package org.tamacat.monitor.web.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.user.Role;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.user.UserScope;
import org.tamacat.monitor.user.UserService;
import org.tamacat.monitor.user.UserService.UserInput;
import org.tamacat.monitor.web.WebSession;

/**
 * User management (SYSTEM_ADMIN only).
 * <pre>
 * GET    /api/users
 * POST   /api/users                    {loginId, displayName, email, locale, enabled, roles, password}
 * GET    /api/users/{id}               user + serverIds + scopes
 * PUT    /api/users/{id}               {displayName, email, locale, enabled, roles}
 * DELETE /api/users/{id}
 * POST   /api/users/{id}/password      {password}   (must be changed at next login)
 * POST   /api/users/{id}/unlock
 * PUT    /api/users/{id}/assignments   {serverIds:[], scopes:[{serverId, hostgroupId, hostgroupName, includeSubgroups}]}
 * </pre>
 */
@WebServlet(urlPatterns = { "/api/users", "/api/users/*" })
public class UsersApi extends ApiServlet {

	private static final long serialVersionUID = 1L;

	private UserService users() {
		return app().users();
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
		requireRole(req, Role.SYSTEM_ADMIN);
		String[] seg = segments(req);
		if (seg.length == 0) {
			ApiSupport.write(res, 200, ApiSupport.array(users().findAll(), ApiSupport::user));
		} else if (seg.length == 1) {
			long id = id(seg[0]);
			User u = users().get(id);
			var serverIds = Json.createArrayBuilder();
			users().serverIds(id).stream().sorted().forEach(serverIds::add);
			ApiSupport.write(res, 200, Json.createObjectBuilder()
					.add("user", ApiSupport.user(u))
					.add("serverIds", serverIds)
					.add("scopes", ApiSupport.array(users().scopes(id), ApiSupport::scope))
					.build());
		} else {
			throw new NotFoundException(req.getRequestURI());
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
		requireRole(req, Role.SYSTEM_ADMIN);
		String[] seg = segments(req);
		if (seg.length == 0) {
			JsonObject b = ApiSupport.readObject(req);
			User u = users().create(input(b, ApiSupport.str(b, "loginId")), ApiSupport.chars(b, "password"),
					WebSession.actor(req));
			ApiSupport.write(res, 201, ApiSupport.user(u).build());
		} else if (seg.length == 2 && seg[1].equals("password")) {
			JsonObject b = ApiSupport.readObject(req);
			users().resetPassword(id(seg[0]), ApiSupport.chars(b, "password"), WebSession.actor(req));
			ok(res);
		} else if (seg.length == 3 && seg[1].equals("mfa") && seg[2].equals("reset")) {
			// T-602 / T-801: turn off two-factor authentication and remove the passkeys of a user who lost the device
			User target = users().get(id(seg[0]));
			app().mfa().reset(target, WebSession.actor(req));
			app().passkeys().deleteAll(target, WebSession.actor(req));
			ok(res);
		} else if (seg.length == 2 && seg[1].equals("unlock")) {
			users().unlock(id(seg[0]), WebSession.actor(req));
			ok(res);
		} else {
			throw new NotFoundException(req.getRequestURI());
		}
	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse res) throws IOException {
		requireRole(req, Role.SYSTEM_ADMIN);
		String[] seg = segments(req);
		if (seg.length == 1) {
			User u = users().update(id(seg[0]), input(ApiSupport.readObject(req), null), WebSession.actor(req));
			ApiSupport.write(res, 200, ApiSupport.user(u).build());
		} else if (seg.length == 2 && seg[1].equals("assignments")) {
			JsonObject b = ApiSupport.readObject(req);
			users().setAssignments(id(seg[0]), ApiSupport.longSet(b, "serverIds"), scopes(b), WebSession.actor(req));
			ok(res);
		} else {
			throw new NotFoundException(req.getRequestURI());
		}
	}

	@Override
	protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws IOException {
		requireRole(req, Role.SYSTEM_ADMIN);
		String[] seg = segments(req);
		if (seg.length != 1) {
			throw new NotFoundException(req.getRequestURI());
		}
		users().delete(id(seg[0]), WebSession.actor(req));
		ok(res);
	}

	private static UserInput input(JsonObject b, String loginId) {
		return new UserInput(loginId, ApiSupport.str(b, "displayName"), ApiSupport.str(b, "email"),
				ApiSupport.str(b, "locale"), ApiSupport.bool(b, "enabled", true), ApiSupport.roles(b));
	}

	private static List<UserScope> scopes(JsonObject b) {
		List<UserScope> list = new ArrayList<>();
		if (b.get("scopes") instanceof JsonArray a) {
			if (a.size() > 1000) {
				throw new BadRequestException("too many scopes");
			}
			for (JsonValue v : a) {
				if (!(v instanceof JsonObject s)) {
					throw new BadRequestException("scope must be an object");
				}
				String name = ApiSupport.str(s, "hostgroupName");
				list.add(new UserScope(ApiSupport.longValue(s, "serverId"), ApiSupport.longValue(s, "hostgroupId"),
						name == null ? "" : name, ApiSupport.bool(s, "includeSubgroups", false)));
			}
		}
		return list;
	}

	private static void ok(HttpServletResponse res) throws IOException {
		ApiSupport.write(res, 200, Json.createObjectBuilder().add("ok", true).build());
	}
}
