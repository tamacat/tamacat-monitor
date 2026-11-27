package org.tamacat.monitor.web.api;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.tamacat.dao.Page;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.zabbix.HostGroupReader.HostGroupSummary;
import org.tamacat.monitor.zabbix.query.ZabbixQueryService;
import org.tamacat.monitor.zabbix.query.ZabbixQueryService.ServerResult;
import org.tamacat.monitor.zabbix.reader.ActionReader;
import org.tamacat.monitor.zabbix.reader.AlertReader;
import org.tamacat.monitor.zabbix.reader.HistoryReader;
import org.tamacat.monitor.zabbix.reader.HostReader;
import org.tamacat.monitor.zabbix.reader.ItemReader;
import org.tamacat.monitor.zabbix.reader.LatestDataReader;
import org.tamacat.monitor.zabbix.reader.ProblemReader;

/**
 * Read API over the Zabbix servers (P2). Every request is limited to the servers and host groups
 * the user may access (the scope is applied in the readers).
 * <pre>
 * GET /api/zabbix/servers                                 accessible servers
 * GET /api/zabbix/hostgroups?server=&amp;q=&amp;limit=            (server may repeat; none = all)
 * GET /api/zabbix/hosts?server=&amp;q=&amp;group=&amp;status=&amp;limit=
 * GET /api/zabbix/problems?server=&amp;q=&amp;severity=&amp;ack=&amp;limit=
 * GET /api/zabbix/alerts?server=&amp;from=&amp;to=&amp;status=&amp;q=&amp;limit=   (ISO-8601 instants, at most 31 days)
 * GET /api/zabbix/servers/{id}/hostgroups/{groupId}
 * GET /api/zabbix/servers/{id}/hosts/{hostId}
 * GET /api/zabbix/servers/{id}/items?host=&amp;q=&amp;status=&amp;limit=&amp;offset=
 * GET /api/zabbix/servers/{id}/latest?group=&amp;host=&amp;q=&amp;tag=&amp;limit=&amp;offset=   latest data (last two values)
 * GET /api/zabbix/servers/{id}/history?item=&amp;from=&amp;to=&amp;q=&amp;exclude=&amp;min=&amp;max=&amp;limit=&amp;offset=
 *                                                         history log (item may repeat; at most 31 days)
 * GET /api/zabbix/servers/{id}/graph?item=&amp;from=&amp;to=&amp;source=auto|history|trends&amp;points=
 * GET /api/zabbix/servers/{id}/actions?eventsource=&amp;q=      (server administrators)
 * GET /api/zabbix/servers/{id}/actions/{actionId}          (server administrators)
 * </pre>
 * Multi-server answers: {"results":[{"serverId","serverName","zabbixVersion","error"?,"items":[...]}]}.
 */
@WebServlet(urlPatterns = "/api/zabbix/*")
public class ZabbixApi extends ApiServlet {

	private static final long serialVersionUID = 1L;

	private ZabbixQueryService zabbix() {
		return app().zabbix();
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
		User user = user(req);
		String[] seg = segments(req);
		if (seg.length == 1) {
			switch (seg[0]) {
				case "servers" -> servers(res, user);
				case "hostgroups" -> write(res, zabbix().hostGroups(user, serverIds(req), str(req, "q"), page(req, 500)),
						ZabbixApi::hostGroup);
				case "hosts" -> write(res, zabbix().hosts(user, serverIds(req), new HostReader.HostQuery(str(req, "q"),
						longParam(req, "group"), intParam(req, "status"), page(req, 200))), ZabbixApi::host);
				case "problems" -> write(res, zabbix().problems(user, serverIds(req), new ProblemReader.ProblemQuery(
						intParam(req, "severity"), str(req, "q"), longParam(req, "host"), boolParam(req, "ack"), page(req, 500))),
						ZabbixApi::problem);
				case "alerts" -> write(res, zabbix().alerts(user, serverIds(req), alertQuery(req)), ZabbixApi::alert);
				default -> throw new NotFoundException(req.getRequestURI());
			}
			return;
		}
		if (seg.length >= 3 && seg[0].equals("servers")) {
			long serverId = id(seg[1]);
			if (seg.length == 4 && seg[2].equals("hostgroups")) {
				ApiSupport.write(res, 200, hostGroup(zabbix().hostGroup(user, serverId, id(seg[3]))).build());
				return;
			}
			if (seg.length == 4 && seg[2].equals("hosts")) {
				ApiSupport.write(res, 200, hostDetail(zabbix().host(user, serverId, id(seg[3]))).build());
				return;
			}
			if (seg.length == 3 && seg[2].equals("items")) {
				var items = zabbix().items(user, serverId, new ItemReader.ItemQuery(longParam(req, "host"), str(req, "q"),
						intParam(req, "status"), page(req, ItemReader.MAX_PAGE)));
				ApiSupport.write(res, 200, ApiSupport.array(items, ZabbixApi::item));
				return;
			}
			if (seg.length == 3 && seg[2].equals("latest")) {
				var rows = zabbix().latest(user, serverId, new LatestDataReader.LatestQuery(longParam(req, "group"),
						longParam(req, "host"), str(req, "q"), str(req, "tag"), page(req, LatestDataReader.MAX_PAGE)));
				ApiSupport.write(res, 200, ApiSupport.array(rows, ZabbixApi::latest));
				return;
			}
			if (seg.length == 3 && seg[2].equals("history")) {
				history(req, res, user, serverId);
				return;
			}
			if (seg.length == 3 && seg[2].equals("graph")) {
				graph(req, res, user, serverId);
				return;
			}
			if (seg.length == 4 && seg[2].equals("items")) {
				var d = app().itemWrites().get(user, serverId, id(seg[3]));
				JsonArrayBuilder tags = Json.createArrayBuilder();
				d.tags().forEach(t -> tags.add(Json.createObjectBuilder().add("tag", t.tag()).add("value", nz(t.value()))));
				ApiSupport.write(res, 200, Json.createObjectBuilder().add("item", item(d.summary())).add("history", nz(d.history()))
						.add("trends", nz(d.trends())).add("description", nz(d.description())).add("editable", d.editable())
						.add("tags", tags).build());
				return;
			}
			if (seg.length == 3 && seg[2].equals("actions")) {
				var actions = zabbix().actions(user, serverId, intParam(req, "eventsource"), str(req, "q"), page(req, 500));
				ApiSupport.write(res, 200, ApiSupport.array(actions, ZabbixApi::action));
				return;
			}
			// templates (T-601)
			if (seg.length == 3 && seg[2].equals("templates")) {
				JsonArrayBuilder arr = Json.createArrayBuilder();
				for (var t : app().templateLinks().templates(user, serverId, str(req, "q"), page(req, 200))) {
					JsonObjectBuilder b = Json.createObjectBuilder().add("templateId", t.templateId()).add("host", t.host())
							.add("name", t.name()).add("itemCount", t.itemCount());
					nullable(b, "reason", t.reason());
					arr.add(b);
				}
				ApiSupport.write(res, 200, arr.build());
				return;
			}
			if (seg.length == 5 && seg[2].equals("hosts") && seg[4].equals("templates")) {
				JsonArrayBuilder arr = Json.createArrayBuilder();
				app().templateLinks().linked(user, serverId, id(seg[3])).forEach(t -> arr.add(Json.createObjectBuilder()
						.add("templateId", t.templateId()).add("host", t.host()).add("name", t.name())));
				ApiSupport.write(res, 200, arr.build());
				return;
			}
			if (seg.length == 3 && seg[2].equals("action-choices")) {
				ApiSupport.write(res, 200, ActionJson.choices(app().actionWrites().choices(user, serverId)).build());
				return;
			}
			if (seg.length == 5 && seg[2].equals("actions") && seg[4].equals("edit")) {
				ApiSupport.write(res, 200, ActionJson.write(app().actionWrites().editable(user, serverId, id(seg[3]))).build());
				return;
			}
			if (seg.length == 4 && seg[2].equals("actions")) {
				ApiSupport.write(res, 200, actionDetail(zabbix().action(user, serverId, id(seg[3]))).build());
				return;
			}
		}
		throw new NotFoundException(req.getRequestURI());
	}

	// ---- writes (P4): host groups ----
	// POST   /api/zabbix/servers/{id}/hostgroups          {name, preview}
	// PUT    /api/zabbix/servers/{id}/hostgroups/{gid}    {expectedName, name, preview}
	// DELETE /api/zabbix/servers/{id}/hostgroups/{gid}?expectedName=&preview=

	// ---- writes (P4): hosts ----
	// POST /api/zabbix/servers/{id}/hosts            {host, name, description, status, groupIds, tags, interface, preview}
	// PUT  /api/zabbix/servers/{id}/hosts/{hostId}   {expectedHost, expectedName, name, description, status, groupIds, tags,
	//                                                 interface, preview}  (absent fields are unchanged)

	private static java.util.Set<Long> groupIdsOf(jakarta.json.JsonObject b) {
		return b.containsKey("groupIds") ? ApiSupport.longSet(b, "groupIds") : null;
	}

	private static java.util.List<org.tamacat.monitor.zabbix.write.HostWriter.Tag> tagsOf(jakarta.json.JsonObject b) {
		if (!(b.get("tags") instanceof jakarta.json.JsonArray a)) {
			return null;
		}
		var list = new java.util.ArrayList<org.tamacat.monitor.zabbix.write.HostWriter.Tag>();
		for (var v : a) {
			if (!(v instanceof jakarta.json.JsonObject t)) {
				throw new BadRequestException("tag");
			}
			list.add(new org.tamacat.monitor.zabbix.write.HostWriter.Tag(ApiSupport.str(t, "tag"), ApiSupport.str(t, "value")));
		}
		return list;
	}

	private static org.tamacat.monitor.zabbix.write.HostWriter.InterfaceInput interfaceOf(jakarta.json.JsonObject b) {
		if (!(b.get("interface") instanceof jakarta.json.JsonObject i)) {
			return null;
		}
		return new org.tamacat.monitor.zabbix.write.HostWriter.InterfaceInput(i.containsKey("type") ? (int) ApiSupport.longValue(i, "type") : 1,
				ApiSupport.bool(i, "useIp", true), ApiSupport.str(i, "ip"), ApiSupport.str(i, "dns"), ApiSupport.str(i, "port"));
	}

	private static java.util.List<org.tamacat.monitor.zabbix.write.HostWriter.Tag> itemTagsOf(jakarta.json.JsonObject b) {
		return tagsOf(b);
	}

	private static Integer statusOf(jakarta.json.JsonObject b) {
		return b.containsKey("status") && !b.isNull("status") ? (int) ApiSupport.longValue(b, "status") : null;
	}

	// ---- writes (P4): trigger actions (server administrators) ----
	// GET  /api/zabbix/servers/{id}/action-choices             user groups, users, media types
	// GET  /api/zabbix/servers/{id}/actions/{actionId}/edit    {input, reason}
	// POST /api/zabbix/servers/{id}/actions                    {...definition (ActionJson), preview}
	// PUT  /api/zabbix/servers/{id}/actions/{actionId}         {expectedName, ...definition, preview}

	// ---- writes (P6): template links (T-601) ----
	// GET    /api/zabbix/servers/{id}/templates?q=&limit=                      templates and whether tamacat-monitor can link them
	// GET    /api/zabbix/servers/{id}/hosts/{hostId}/templates                linked templates
	// POST   /api/zabbix/servers/{id}/hosts/{hostId}/templates                {templateId, expectedHost, preview}
	// DELETE /api/zabbix/servers/{id}/hosts/{hostId}/templates/{templateId}?expectedHost=&clear=&preview=

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
		String[] seg = segments(req);
		if (seg.length == 5 && seg[0].equals("servers") && seg[2].equals("hosts") && seg[4].equals("templates")) {
			var b = ApiSupport.readObject(req);
			writeResult(res, app().templateLinks().link(user(req), org.tamacat.monitor.web.WebSession.actor(req), id(seg[1]), id(seg[3]),
					ApiSupport.str(b, "expectedHost"), ApiSupport.longValue(b, "templateId"), ApiSupport.bool(b, "preview", false)));
			return;
		}
		if (seg.length == 3 && seg[0].equals("servers") && seg[2].equals("actions")) {
			var b = ApiSupport.readObject(req);
			writeResult(res, app().actionWrites().create(user(req), org.tamacat.monitor.web.WebSession.actor(req), id(seg[1]),
					ActionJson.read(b), ApiSupport.bool(b, "preview", false)));
			return;
		}
		if (seg.length == 3 && seg[0].equals("servers") && seg[2].equals("items")) {
			var b = ApiSupport.readObject(req);
			Integer status = statusOf(b);
			var in = new org.tamacat.monitor.zabbix.write.ItemWriter.ItemInput(ApiSupport.longValue(b, "hostId"),
					ApiSupport.str(b, "name"), ApiSupport.str(b, "key"), (int) ApiSupport.longValue(b, "type"),
					(int) ApiSupport.longValue(b, "valueType"), ApiSupport.str(b, "delay"), ApiSupport.str(b, "history"),
					ApiSupport.str(b, "trends"), ApiSupport.str(b, "units"), ApiSupport.str(b, "description"),
					status == null ? 0 : status, itemTagsOf(b));
			writeResult(res, app().itemWrites().create(user(req), org.tamacat.monitor.web.WebSession.actor(req), id(seg[1]), in,
					ApiSupport.bool(b, "preview", false)));
			return;
		}
		if (seg.length == 3 && seg[0].equals("servers") && seg[2].equals("hosts")) {
			var b = ApiSupport.readObject(req);
			Integer status = statusOf(b);
			var in = new org.tamacat.monitor.zabbix.write.HostWriter.HostInput(ApiSupport.str(b, "host"), ApiSupport.str(b, "name"),
					ApiSupport.str(b, "description"), status == null ? 0 : status, groupIdsOf(b), tagsOf(b), interfaceOf(b));
			writeResult(res, app().hostWrites().create(user(req), org.tamacat.monitor.web.WebSession.actor(req), id(seg[1]), in,
					ApiSupport.bool(b, "preview", false)));
			return;
		}
		if (seg.length == 3 && seg[0].equals("servers") && seg[2].equals("hostgroups")) {
			var body = ApiSupport.readObject(req);
			var r = app().hostGroupWrites().create(user(req), org.tamacat.monitor.web.WebSession.actor(req), id(seg[1]),
					ApiSupport.str(body, "name"), ApiSupport.bool(body, "preview", false));
			writeResult(res, r);
			return;
		}
		throw new NotFoundException(req.getRequestURI());
	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse res) throws IOException {
		String[] seg = segments(req);
		if (seg.length == 4 && seg[0].equals("servers") && seg[2].equals("actions")) {
			var b = ApiSupport.readObject(req);
			writeResult(res, app().actionWrites().update(user(req), org.tamacat.monitor.web.WebSession.actor(req), id(seg[1]),
					id(seg[3]), ApiSupport.str(b, "expectedName"), ActionJson.read(b), ApiSupport.bool(b, "preview", false)));
			return;
		}
		if (seg.length == 4 && seg[0].equals("servers") && seg[2].equals("items")) {
			var b = ApiSupport.readObject(req);
			var u = new org.tamacat.monitor.zabbix.write.ItemWriter.ItemUpdate(ApiSupport.str(b, "expectedKey"),
					ApiSupport.str(b, "expectedName"), ApiSupport.str(b, "name"), ApiSupport.str(b, "delay"),
					ApiSupport.str(b, "history"), ApiSupport.str(b, "trends"), ApiSupport.str(b, "units"),
					ApiSupport.str(b, "description"), statusOf(b), itemTagsOf(b));
			writeResult(res, app().itemWrites().update(user(req), org.tamacat.monitor.web.WebSession.actor(req), id(seg[1]),
					id(seg[3]), u, ApiSupport.bool(b, "preview", false)));
			return;
		}
		if (seg.length == 4 && seg[0].equals("servers") && seg[2].equals("hosts")) {
			var b = ApiSupport.readObject(req);
			var u = new org.tamacat.monitor.zabbix.write.HostWriter.HostUpdate(ApiSupport.str(b, "expectedHost"),
					ApiSupport.str(b, "expectedName"), ApiSupport.str(b, "name"), ApiSupport.str(b, "description"), statusOf(b),
					groupIdsOf(b), tagsOf(b), interfaceOf(b));
			writeResult(res, app().hostWrites().update(user(req), org.tamacat.monitor.web.WebSession.actor(req), id(seg[1]),
					id(seg[3]), u, ApiSupport.bool(b, "preview", false)));
			return;
		}
		if (seg.length == 4 && seg[0].equals("servers") && seg[2].equals("hostgroups")) {
			var body = ApiSupport.readObject(req);
			var r = app().hostGroupWrites().rename(user(req), org.tamacat.monitor.web.WebSession.actor(req), id(seg[1]),
					id(seg[3]), ApiSupport.str(body, "expectedName"), ApiSupport.str(body, "name"),
					ApiSupport.bool(body, "preview", false));
			writeResult(res, r);
			return;
		}
		throw new NotFoundException(req.getRequestURI());
	}

	@Override
	protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws IOException {
		String[] seg = segments(req);
		if (seg.length == 4 && seg[0].equals("servers") && seg[2].equals("hostgroups")) {
			var r = app().hostGroupWrites().delete(user(req), org.tamacat.monitor.web.WebSession.actor(req), id(seg[1]),
					id(seg[3]), req.getParameter("expectedName"), Boolean.parseBoolean(req.getParameter("preview")));
			writeResult(res, r);
			return;
		}
		if (seg.length == 6 && seg[0].equals("servers") && seg[2].equals("hosts") && seg[4].equals("templates")) {
			writeResult(res, app().templateLinks().unlink(user(req), org.tamacat.monitor.web.WebSession.actor(req), id(seg[1]),
					id(seg[3]), req.getParameter("expectedHost"), id(seg[5]), Boolean.parseBoolean(req.getParameter("clear")),
					Boolean.parseBoolean(req.getParameter("preview"))));
			return;
		}
		// DELETE /api/zabbix/servers/{id}/hosts/{hostId}?expectedHost=&preview=      (T-408)
		// DELETE /api/zabbix/servers/{id}/items/{itemId}?expectedKey=&preview=
		// DELETE /api/zabbix/servers/{id}/actions/{actionId}?expectedName=&preview=
		if (seg.length == 4 && seg[0].equals("servers")) {
			var actor = org.tamacat.monitor.web.WebSession.actor(req);
			boolean preview = Boolean.parseBoolean(req.getParameter("preview"));
			switch (seg[2]) {
				case "hosts" -> {
					writeResult(res, app().hostWrites().delete(user(req), actor, id(seg[1]), id(seg[3]), req.getParameter("expectedHost"),
							preview));
					return;
				}
				case "items" -> {
					writeResult(res, app().itemWrites().delete(user(req), actor, id(seg[1]), id(seg[3]), req.getParameter("expectedKey"),
							preview));
					return;
				}
				case "actions" -> {
					writeResult(res, app().actionWrites().delete(user(req), actor, id(seg[1]), id(seg[3]),
							req.getParameter("expectedName"), preview));
					return;
				}
				default -> {
				}
			}
		}
		throw new NotFoundException(req.getRequestURI());
	}

	private static void writeResult(HttpServletResponse res, org.tamacat.monitor.zabbix.write.ZabbixWriteService.WriteResult<Long> r)
			throws IOException {
		JsonArrayBuilder changes = Json.createArrayBuilder();
		for (var c : r.changes()) {
			JsonObjectBuilder before = Json.createObjectBuilder();
			c.before().forEach(before::add);
			JsonObjectBuilder after = Json.createObjectBuilder();
			c.after().forEach(after::add);
			changes.add(Json.createObjectBuilder().add("table", c.table()).add("operation", c.operation()).add("id", c.id())
					.add("before", before).add("after", after));
		}
		JsonObjectBuilder b = Json.createObjectBuilder().add("preview", r.preview()).add("changes", changes);
		if (r.result() != null) {
			b.add("id", r.result());
		}
		ApiSupport.write(res, 200, b.build());
	}

	private void servers(HttpServletResponse res, User user) throws IOException {
		var a = zabbix().assignments(user);
		JsonArrayBuilder arr = Json.createArrayBuilder();
		for (var s : zabbix().accessibleServers(user)) {
			arr.add(Json.createObjectBuilder().add("serverId", s.serverId()).add("name", s.name())
					.add("zabbixVersion", s.zabbixVersion())
					.add("admin", org.tamacat.monitor.zabbix.access.AccessResolver.isServerAdmin(user, a, s.serverId()))
					.add("writable", org.tamacat.monitor.zabbix.ZabbixServerService.writable(s)
							&& org.tamacat.monitor.zabbix.ZabbixVersion.fromLabel(s.zabbixVersion()).isPresent()));
		}
		ApiSupport.write(res, 200, arr.build());
	}

	// ---- parameters ----

	private static Set<Long> serverIds(HttpServletRequest req) {
		Set<Long> ids = new LinkedHashSet<>();
		String[] values = req.getParameterValues("server");
		if (values != null) {
			for (String v : values) {
				for (String part : v.split(",")) {
					if (!part.isBlank()) {
						ids.add(parseLong(part.strip()));
					}
				}
			}
		}
		return ids;
	}

	private static String str(HttpServletRequest req, String name) {
		String v = req.getParameter(name);
		return v == null || v.isBlank() ? null : v.length() > 200 ? v.substring(0, 200) : v;
	}

	private static Long longParam(HttpServletRequest req, String name) {
		String v = str(req, name);
		return v == null ? null : parseLong(v);
	}

	private static Integer intParam(HttpServletRequest req, String name) {
		Long v = longParam(req, name);
		if (v != null && (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE)) {
			throw new BadRequestException(name);
		}
		return v == null ? null : v.intValue();
	}

	private static Boolean boolParam(HttpServletRequest req, String name) {
		String v = str(req, name);
		return v == null ? null : Boolean.parseBoolean(v);
	}

	private static long parseLong(String v) {
		try {
			return Long.parseLong(v);
		} catch (NumberFormatException e) {
			throw new BadRequestException("not a number");
		}
	}

	// ---- collected values ----

	private void history(HttpServletRequest req, HttpServletResponse res, User user, long serverId) throws IOException {
		Instant to = instant(req, "to", Instant.now());
		var r = zabbix().history(user, serverId, new HistoryReader.HistoryQuery(itemIds(req),
				instant(req, "from", to.minus(Duration.ofHours(1))), to, str(req, "q"), Boolean.TRUE.equals(boolParam(req, "exclude")),
				decimalParam(req, "min"), decimalParam(req, "max"), page(req, HistoryReader.MAX_PAGE)));
		JsonArrayBuilder rows = Json.createArrayBuilder();
		for (var h : r.rows()) {
			JsonObjectBuilder b = Json.createObjectBuilder().add("itemId", h.itemId()).add("clock", h.clock().toString())
					.add("ns", h.ns());
			nullable(b, "value", h.value());
			if (h.logEventId() != null) {
				nullable(b, "logTime", h.logTime() == null ? null : h.logTime().toString());
				b.add("source", nz(h.source())).add("severity", h.severity()).add("logEventId", h.logEventId());
			}
			rows.add(b);
		}
		ApiSupport.write(res, 200, Json.createObjectBuilder().add("items", ApiSupport.array(r.items(), ZabbixApi::itemRef))
				.add("rows", rows).build());
	}

	/** Series points are [epoch seconds, min, avg, max]. */
	private void graph(HttpServletRequest req, HttpServletResponse res, User user, long serverId) throws IOException {
		Instant to = instant(req, "to", Instant.now());
		Integer points = intParam(req, "points");
		var series = zabbix().graph(user, serverId, new HistoryReader.GraphQuery(itemIds(req),
				instant(req, "from", to.minus(Duration.ofHours(1))), to, HistoryReader.Source.of(str(req, "source")),
				points == null ? 500 : points));
		JsonArrayBuilder arr = Json.createArrayBuilder();
		for (var s : series) {
			JsonArrayBuilder pts = Json.createArrayBuilder();
			s.points().forEach(p -> pts.add(Json.createArrayBuilder().add(p.clock().getEpochSecond()).add(num(p.min()))
					.add(num(p.avg())).add(num(p.max()))));
			arr.add(Json.createObjectBuilder().add("item", itemRef(s.item())).add("source", s.source())
					.add("bucket", s.bucket()).add("points", pts));
		}
		ApiSupport.write(res, 200, Json.createObjectBuilder().add("series", arr).build());
	}

	/** "item" parameters (repeated or comma-separated), at most HistoryReader.MAX_ITEMS. */
	private static List<Long> itemIds(HttpServletRequest req) {
		Set<Long> ids = new LinkedHashSet<>();
		String[] values = req.getParameterValues("item");
		if (values != null) {
			for (String v : values) {
				for (String part : v.split(",")) {
					if (!part.isBlank()) {
						ids.add(parseLong(part.strip()));
					}
				}
			}
		}
		if (ids.isEmpty() || ids.size() > HistoryReader.MAX_ITEMS) {
			throw new BadRequestException("item");
		}
		return List.copyOf(ids);
	}

	private static java.math.BigDecimal decimalParam(HttpServletRequest req, String name) {
		String v = str(req, name);
		try {
			return v == null ? null : new java.math.BigDecimal(v.strip());
		} catch (NumberFormatException e) {
			throw new BadRequestException(name);
		}
	}

	private static Page page(HttpServletRequest req, int maxLimit) {
		Integer limit = intParam(req, "limit");
		Integer offset = intParam(req, "offset");
		int l = limit == null ? Math.min(100, maxLimit) : Math.clamp(limit, 1, maxLimit);
		return new Page(offset == null ? 0 : Math.clamp(offset, 0, 100_000), l);
	}

	private static AlertReader.AlertQuery alertQuery(HttpServletRequest req) {
		Instant to = instant(req, "to", Instant.now());
		Instant from = instant(req, "from", to.minus(Duration.ofDays(1)));
		return new AlertReader.AlertQuery(from, to, intParam(req, "status"), longParam(req, "action"), str(req, "q"),
				page(req, 500));
	}

	private static Instant instant(HttpServletRequest req, String name, Instant def) {
		String v = str(req, name);
		try {
			return v == null ? def : Instant.parse(v);
		} catch (DateTimeParseException e) {
			throw new BadRequestException(name);
		}
	}

	// ---- representations ----

	private static <T> void write(HttpServletResponse res, List<ServerResult<T>> results, Function<T, JsonObjectBuilder> fn)
			throws IOException {
		JsonArrayBuilder arr = Json.createArrayBuilder();
		for (ServerResult<T> r : results) {
			JsonObjectBuilder b = Json.createObjectBuilder().add("serverId", r.serverId()).add("serverName", r.serverName())
					.add("zabbixVersion", r.zabbixVersion()).add("items", ApiSupport.array(r.items(), fn));
			if (r.errorKey() != null) {
				b.add("error", r.errorKey());
			}
			arr.add(b);
		}
		ApiSupport.write(res, 200, Json.createObjectBuilder().add("results", arr).build());
	}

	private static JsonObjectBuilder hostGroup(HostGroupSummary g) {
		return Json.createObjectBuilder().add("groupId", g.groupId()).add("name", g.name()).add("hostCount", g.hostCount());
	}

	private static JsonObjectBuilder host(HostReader.HostSummary h) {
		JsonArrayBuilder groups = Json.createArrayBuilder();
		h.groups().forEach(groups::add);
		JsonArrayBuilder groupIds = Json.createArrayBuilder();
		h.groupIds().forEach(groupIds::add);
		JsonArrayBuilder ifs = Json.createArrayBuilder();
		h.interfaces().forEach(i -> ifs.add(Json.createObjectBuilder().add("type", i.type()).add("main", i.main())
				.add("address", nz(i.address())).add("useIp", i.useIp()).add("ip", nz(i.ip())).add("dns", nz(i.dns()))
				.add("port", nz(i.port())).add("available", i.available()).add("error", nz(i.error()))));
		JsonObjectBuilder b = Json.createObjectBuilder().add("hostId", h.hostId()).add("host", h.host()).add("name", h.name())
				.add("status", h.status()).add("maintenance", h.maintenanceStatus() == 1).add("groups", groups)
				.add("groupIds", groupIds).add("interfaces", ifs);
		nullable(b, "proxy", h.proxy());
		return b;
	}

	private static JsonObjectBuilder hostDetail(HostReader.HostDetail d) {
		JsonArrayBuilder tags = Json.createArrayBuilder();
		d.tags().forEach(t -> tags.add(Json.createObjectBuilder().add("tag", t.tag()).add("value", nz(t.value()))));
		JsonArrayBuilder templates = Json.createArrayBuilder();
		d.templates().forEach(templates::add);
		return Json.createObjectBuilder().add("host", host(d.summary())).add("description", nz(d.description()))
				.add("tags", tags).add("templates", templates).add("itemCount", d.itemCount())
				.add("triggerCount", d.triggerCount()).add("problemCount", d.problemCount());
	}

	private static JsonObjectBuilder item(ItemReader.ItemSummary i) {
		JsonObjectBuilder b = Json.createObjectBuilder().add("itemId", i.itemId()).add("hostId", i.hostId())
				.add("host", i.host()).add("hostName", i.hostName()).add("name", i.name()).add("key", i.key())
				.add("type", i.type()).add("valueType", i.valueType()).add("delay", nz(i.delay())).add("units", nz(i.units()))
				.add("status", i.status()).add("state", i.state()).add("error", nz(i.error()));
		nullable(b, "lastClock", i.lastClock() == null ? null : i.lastClock().toString());
		nullable(b, "lastValue", i.lastValue());
		return b;
	}

	private static JsonObjectBuilder latest(LatestDataReader.LatestRow i) {
		JsonArrayBuilder tags = Json.createArrayBuilder();
		i.tags().forEach(t -> tags.add(Json.createObjectBuilder().add("tag", t.tag()).add("value", nz(t.value()))));
		JsonObjectBuilder b = Json.createObjectBuilder().add("itemId", i.itemId()).add("hostId", i.hostId())
				.add("host", i.host()).add("hostName", i.hostName()).add("name", i.name()).add("key", i.key())
				.add("valueType", i.valueType()).add("units", nz(i.units())).add("delay", nz(i.delay())).add("state", i.state())
				.add("error", nz(i.error())).add("tags", tags);
		nullable(b, "lastClock", i.lastClock() == null ? null : i.lastClock().toString());
		nullable(b, "lastValue", i.lastValue());
		nullable(b, "prevValue", i.prevValue());
		return b;
	}

	private static JsonObjectBuilder itemRef(HistoryReader.ItemRef i) {
		return Json.createObjectBuilder().add("itemId", i.itemId()).add("hostId", i.hostId()).add("host", i.host())
				.add("hostName", i.hostName()).add("name", i.name()).add("key", i.key()).add("valueType", i.valueType())
				.add("units", nz(i.units()));
	}

	/** A finite number, or JSON null. */
	private static jakarta.json.JsonValue num(double d) {
		return Double.isFinite(d) ? Json.createValue(d) : jakarta.json.JsonValue.NULL;
	}

	private static JsonObjectBuilder problem(ProblemReader.ProblemRow p) {
		JsonArrayBuilder hosts = Json.createArrayBuilder();
		p.hosts().forEach(hosts::add);
		return Json.createObjectBuilder().add("eventId", p.eventId()).add("name", p.name()).add("severity", p.severity())
				.add("clock", p.clock().toString()).add("acknowledged", p.acknowledged()).add("suppressed", p.suppressed())
				.add("hosts", hosts);
	}

	private static JsonObjectBuilder alert(AlertReader.AlertRow a) {
		JsonObjectBuilder b = Json.createObjectBuilder().add("alertId", a.alertId()).add("clock", a.clock().toString())
				.add("sendTo", nz(a.sendTo())).add("status", a.status()).add("retries", a.retries())
				.add("error", nz(a.error())).add("subject", nz(a.subject())).add("alertType", a.alertType());
		nullable(b, "actionName", a.actionName());
		nullable(b, "eventName", a.eventName());
		nullable(b, "mediaType", a.mediaType());
		nullable(b, "userName", a.userName());
		if (a.severity() != null) {
			b.add("severity", a.severity());
		}
		return b;
	}

	private static JsonObjectBuilder action(ActionReader.ActionSummary a) {
		return Json.createObjectBuilder().add("actionId", a.actionId()).add("name", a.name()).add("eventSource", a.eventSource())
				.add("status", a.status()).add("escPeriod", nz(a.escPeriod())).add("conditionCount", a.conditionCount())
				.add("operationCount", a.operationCount());
	}

	private static JsonObjectBuilder actionDetail(ActionReader.ActionDetail d) {
		JsonArrayBuilder conditions = Json.createArrayBuilder();
		d.conditions().forEach(c -> {
			JsonObjectBuilder b = Json.createObjectBuilder().add("conditionType", c.conditionType())
					.add("operator", c.operator()).add("value", nz(c.value())).add("value2", nz(c.value2()));
			nullable(b, "valueName", c.valueName());
			conditions.add(b);
		});
		JsonArrayBuilder operations = Json.createArrayBuilder();
		d.operations().forEach(o -> {
			JsonArrayBuilder groups = Json.createArrayBuilder();
			o.userGroups().forEach(groups::add);
			JsonArrayBuilder users = Json.createArrayBuilder();
			o.users().forEach(users::add);
			JsonObjectBuilder b = Json.createObjectBuilder().add("operationType", o.operationType())
					.add("recovery", o.recovery()).add("stepFrom", o.stepFrom()).add("stepTo", o.stepTo())
					.add("escPeriod", nz(o.escPeriod())).add("userGroups", groups).add("users", users);
			nullable(b, "mediaType", o.mediaType());
			nullable(b, "subject", o.subject());
			operations.add(b);
		});
		return Json.createObjectBuilder().add("action", action(d.summary())).add("evalType", d.evalType())
				.add("formula", nz(d.formula())).add("conditions", conditions).add("operations", operations);
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	private static void nullable(JsonObjectBuilder b, String key, String value) {
		if (value == null) {
			b.addNull(key);
		} else {
			b.add(key, value);
		}
	}
}
