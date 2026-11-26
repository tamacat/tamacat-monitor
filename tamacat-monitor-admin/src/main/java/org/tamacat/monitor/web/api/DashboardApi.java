package org.tamacat.monitor.web.api;

import java.io.IOException;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.zabbix.query.DashboardService;
import org.tamacat.monitor.zabbix.reader.DashboardReader.Summary;

/**
 * Dashboards (P3). Figures are limited to the user's scope.
 * <pre>
 * GET /api/dashboard                 overview: one card per server + totals
 * GET /api/dashboard/servers/{id}    one server: summary, top problems, unavailable hosts
 * </pre>
 */
@WebServlet(urlPatterns = { "/api/dashboard", "/api/dashboard/*" })
public class DashboardApi extends ApiServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
		User user = user(req);
		DashboardService dashboard = app().dashboard();
		String[] seg = segments(req);
		if (seg.length == 0) {
			DashboardService.Overview o = dashboard.overview(user);
			JsonArrayBuilder cards = Json.createArrayBuilder();
			o.servers().forEach(c -> cards.add(card(c)));
			ApiSupport.write(res, 200, Json.createObjectBuilder().add("servers", cards).add("totals", summary(o.totals()))
					.add("generatedAt", o.generatedAt().toString()).build());
			return;
		}
		if (seg.length == 2 && seg[0].equals("servers")) {
			DashboardService.ServerDetail d = dashboard.server(user, id(seg[1]));
			JsonArrayBuilder problems = Json.createArrayBuilder();
			d.problems().forEach(p -> {
				JsonArrayBuilder hosts = Json.createArrayBuilder();
				p.hosts().forEach(hosts::add);
				problems.add(Json.createObjectBuilder().add("eventId", p.eventId()).add("name", p.name())
						.add("severity", p.severity()).add("clock", p.clock().toString())
						.add("acknowledged", p.acknowledged()).add("hosts", hosts));
			});
			JsonArrayBuilder unavailable = Json.createArrayBuilder();
			d.unavailableHosts().forEach(h -> unavailable.add(Json.createObjectBuilder().add("hostId", h.hostId())
					.add("name", h.name()).add("interfaceType", h.interfaceType())
					.add("error", h.error() == null ? "" : h.error())));
			ApiSupport.write(res, 200, Json.createObjectBuilder().add("card", card(d.card())).add("problems", problems)
					.add("unavailableHosts", unavailable).build());
			return;
		}
		throw new NotFoundException(req.getRequestURI());
	}

	private static JsonObjectBuilder card(DashboardService.ServerCard c) {
		JsonObjectBuilder b = Json.createObjectBuilder().add("serverId", c.serverId()).add("name", c.name())
				.add("status", c.status()).add("stale", c.stale()).add("admin", c.admin());
		if (c.zabbixVersion() == null) {
			b.addNull("zabbixVersion");
		} else {
			b.add("zabbixVersion", c.zabbixVersion());
		}
		if (c.summary() == null) {
			b.addNull("summary");
		} else {
			b.add("summary", summary(c.summary()));
		}
		if (c.collectedAt() == null) {
			b.addNull("collectedAt");
		} else {
			b.add("collectedAt", c.collectedAt().toString());
		}
		return b;
	}

	private static JsonObjectBuilder summary(Summary s) {
		JsonArrayBuilder sev = Json.createArrayBuilder();
		for (int n : s.problemsBySeverity()) {
			sev.add(n);
		}
		return Json.createObjectBuilder().add("hostsMonitored", s.hostsMonitored()).add("hostsDisabled", s.hostsDisabled())
				.add("hostsInMaintenance", s.hostsInMaintenance()).add("hostsUnavailable", s.hostsUnavailable())
				.add("problemsBySeverity", sev).add("problems", s.problems()).add("unacknowledged", s.unacknowledged())
				.add("itemsEnabled", s.itemsEnabled()).add("itemsUnsupported", s.itemsUnsupported());
	}
}
