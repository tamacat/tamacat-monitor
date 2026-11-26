package org.tamacat.monitor.zabbix.reader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.tamacat.dao.Conditions;
import org.tamacat.dao.Page;
import org.tamacat.dao.Param;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.dao.Sort;
import org.tamacat.sql.LikeMode;
import org.tamacat.sql.SqlOperations;
import org.tamacat.monitor.zabbix.ZabbixSchema;
import org.tamacat.monitor.zabbix.access.AccessScope;

/**
 * Reads hosts (status 0 monitored / 1 not monitored; templates, proxies and host prototypes
 * are excluded).
 */
public final class HostReader {

	/** Search conditions; null means "any". */
	public record HostQuery(String text, Long groupId, Integer status, Page page) {
	}

	/** Interface: type 1 agent, 2 SNMP, 3 IPMI, 4 JMX; available 0 unknown, 1 available, 2 unavailable. */
	public record HostInterface(int type, boolean main, String ip, String dns, boolean useIp, String port, int available,
			String error) {
		public String address() {
			return useIp ? ip : dns;
		}
	}

	public record HostSummary(long hostId, String host, String name, int status, int maintenanceStatus, String proxy,
			List<String> groups, List<Long> groupIds, List<HostInterface> interfaces) {
	}

	public record Tag(String tag, String value) {
	}

	public record HostDetail(HostSummary summary, String description, List<Tag> tags, List<String> templates,
			int itemCount, int triggerCount, int problemCount) {
	}

	private final ZabbixSchema schema;

	public HostReader(ZabbixSchema schema) {
		this.schema = schema;
	}

	private String select() {
		return "select h.hostid, h.host, h.name, h.status, h.maintenance_status, " + schema.proxyName()
				+ " as proxy_name from hosts h " + schema.proxyJoin();
	}

	public List<HostSummary> search(SqlOperations db, AccessScope scope, HostQuery q) {
		List<Param> where = new ArrayList<>();
		where.add(Param.of("h.status in (0, 1) and h.flags in (0, 4)"));
		where.add(scope.hostCondition("h.hostid"));
		if (q.status() != null) {
			where.add(Conditions.eq("h.status", q.status()));
		}
		if (q.groupId() != null) {
			where.add(Param.of("exists (select 1 from hosts_groups fg where fg.hostid = h.hostid and fg.groupid = ?)",
					q.groupId()));
		}
		if (q.text() != null && !q.text().isBlank()) {
			String t = q.text().strip();
			where.add(Conditions.likeIgnoreCase("h.host", t, LikeMode.CONTAINS)
					.or(Conditions.likeIgnoreCase("h.name", t, LikeMode.CONTAINS))
					.or(Param.of("exists (select 1 from interface fi where fi.hostid = h.hostid and (fi.ip like ? escape '!' "
							+ "or lower(fi.dns) like ? escape '!'))",
							org.tamacat.sql.LikeEscape.pattern(t, LikeMode.PREFIX),
							org.tamacat.sql.LikeEscape.pattern(t.toLowerCase(java.util.Locale.ROOT), LikeMode.PREFIX))));
		}
		var sql = SqlBuilder.of(select()).where(where).orderBy(Sort.asc("h.name")).build(db.dialect(), q.page());
		List<Row> rows = db.queryList(sql, HostReader::row);
		return complete(db, rows);
	}

	public Optional<HostDetail> get(SqlOperations db, AccessScope scope, long hostId) {
		var sql = SqlBuilder.of(select())
				.where(Param.of("h.status in (0, 1) and h.flags in (0, 4)"), Conditions.eq("h.hostid", hostId),
						scope.hostCondition("h.hostid"))
				.build();
		Optional<Row> row = db.queryOne(sql, HostReader::row);
		if (row.isEmpty()) {
			return Optional.empty();
		}
		HostSummary summary = complete(db, List.of(row.get())).get(0);
		String description = db.queryOne(SqlBuilder.of("select description from hosts").where(Conditions.eq("hostid", hostId)).build(),
				r -> r.getString("description")).orElse("");
		List<Tag> tags = db.queryList(SqlBuilder.of("select tag, value from host_tag").where(Conditions.eq("hostid", hostId))
				.orderBy(Sort.asc("tag")).build(), r -> new Tag(r.getString("tag"), r.getString("value")));
		List<String> templates = db.queryList(SqlBuilder.of("select t.name from hosts_templates ht join hosts t on t.hostid = ht.templateid")
				.where(Conditions.eq("ht.hostid", hostId)).orderBy(Sort.asc("t.name")).build(), r -> r.getString("name"));
		int items = count(db, "select count(*) from items i where i.hostid = ? and i.flags in (0, 4)", hostId);
		int triggers = count(db, "select count(distinct f.triggerid) from functions f join items i on i.itemid = f.itemid "
				+ "where i.hostid = ?", hostId);
		int problems = count(db, "select count(*) from problem p where p.source = 0 and p.object = 0 and p.r_eventid is null "
				+ "and exists (select 1 from functions f join items i on i.itemid = f.itemid where f.triggerid = p.objectid "
				+ "and i.hostid = ?)", hostId);
		return Optional.of(new HostDetail(summary, description, tags, templates, items, triggers, problems));
	}

	private static int count(SqlOperations db, String sql, long id) {
		return db.queryOne(org.tamacat.dao.PreparedSql.of(sql, id), r -> r.getInt(1)).orElse(0);
	}

	private record Row(long hostId, String host, String name, int status, int maintenance, String proxy) {
	}

	private static Row row(org.tamacat.sql.Row r) throws java.sql.SQLException {
		return new Row(r.getLong("hostid"), r.getString("host"), r.getString("name"), r.getInt("status"),
				r.getInt("maintenance_status"), r.getString("proxy_name"));
	}

	/** Adds groups and interfaces with one query each for the whole page. */
	private static List<HostSummary> complete(SqlOperations db, List<Row> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		Collection<Long> ids = rows.stream().map(Row::hostId).toList();
		Map<Long, List<String>> groups = new HashMap<>();
		Map<Long, List<Long>> groupIds = new HashMap<>();
		db.queryList(SqlBuilder.of("select hg.hostid, g.groupid, g.name from hosts_groups hg join hstgrp g on g.groupid = hg.groupid")
				.where(Conditions.in("hg.hostid", ids)).orderBy(Sort.asc("g.name")).build(),
				r -> new Object[] { r.getLong("hostid"), r.getLong("groupid"), r.getString("name") })
				.forEach(a -> {
					groups.computeIfAbsent((Long) a[0], k -> new ArrayList<>()).add((String) a[2]);
					groupIds.computeIfAbsent((Long) a[0], k -> new ArrayList<>()).add((Long) a[1]);
				});
		Map<Long, List<HostInterface>> interfaces = new HashMap<>();
		db.queryList(SqlBuilder.of("select hostid, type, main, ip, dns, useip, port, available, error from interface")
				.where(Conditions.in("hostid", ids)).orderBy(Sort.desc("main").then("type", Sort.Order.ASC)).build(),
				r -> Map.entry(r.getLong("hostid"), new HostInterface(r.getInt("type"), r.getInt("main") == 1,
						r.getString("ip"), r.getString("dns"), r.getInt("useip") == 1, r.getString("port"),
						r.getInt("available"), r.getString("error"))))
				.forEach(e -> interfaces.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
		Map<Long, HostSummary> out = new LinkedHashMap<>();
		for (Row r : rows) {
			out.put(r.hostId(), new HostSummary(r.hostId(), r.host(), r.name(), r.status(), r.maintenance(), r.proxy(),
					groups.getOrDefault(r.hostId(), List.of()), groupIds.getOrDefault(r.hostId(), List.of()),
					interfaces.getOrDefault(r.hostId(), List.of())));
		}
		return List.copyOf(out.values());
	}
}
