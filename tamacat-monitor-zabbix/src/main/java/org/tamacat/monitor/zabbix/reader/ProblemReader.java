package org.tamacat.monitor.zabbix.reader;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.tamacat.dao.Conditions;
import org.tamacat.dao.Page;
import org.tamacat.dao.Param;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.dao.Sort;
import org.tamacat.sql.LikeMode;
import org.tamacat.sql.SqlOperations;
import org.tamacat.monitor.zabbix.access.AccessScope;

/** Reads open trigger problems (problem.r_eventid is null). */
public final class ProblemReader {

	/** Search conditions; null means "any". */
	public record ProblemQuery(Integer minSeverity, String text, Long hostId, Boolean acknowledged, Page page) {
	}

	/** @param severity 0 not classified .. 5 disaster */
	public record ProblemRow(long eventId, long triggerId, String name, int severity, Instant clock, boolean acknowledged,
			boolean suppressed, List<String> hosts) {
	}

	public List<ProblemRow> search(SqlOperations db, AccessScope scope, ProblemQuery q) {
		List<Param> where = new ArrayList<>();
		where.add(Param.of("p.source = 0 and p.object = 0 and p.r_eventid is null"));
		where.add(scope.triggerCondition("p.objectid"));
		if (q.minSeverity() != null) {
			where.add(Conditions.ge("p.severity", q.minSeverity()));
		}
		if (q.acknowledged() != null) {
			where.add(Conditions.eq("p.acknowledged", q.acknowledged() ? 1 : 0));
		}
		if (q.text() != null && !q.text().isBlank()) {
			where.add(Conditions.likeIgnoreCase("p.name", q.text().strip(), LikeMode.CONTAINS));
		}
		if (q.hostId() != null) {
			where.add(Param.of("exists (select 1 from functions hf join items hi on hi.itemid = hf.itemid "
					+ "where hf.triggerid = p.objectid and hi.hostid = ?)", q.hostId()));
		}
		var sql = SqlBuilder.of("select p.eventid, p.objectid, p.name, p.severity, p.clock, p.acknowledged, "
				+ "(select count(*) from event_suppress es where es.eventid = p.eventid) as suppressed from problem p")
				.where(where)
				.orderBy(Sort.desc("p.clock").then("p.eventid", Sort.Order.DESC))
				.build(db.dialect(), q.page());
		List<ProblemRow> rows = db.queryList(sql, r -> new ProblemRow(r.getLong("eventid"), r.getLong("objectid"),
				r.getString("name"), r.getInt("severity"), r.getEpochSecond("clock"), r.getInt("acknowledged") == 1,
				r.getInt("suppressed") > 0, List.of()));
		if (rows.isEmpty()) {
			return rows;
		}
		Map<Long, Set<String>> hosts = new HashMap<>();
		db.queryList(SqlBuilder.of("select distinct f.triggerid, h.name from functions f join items i on i.itemid = f.itemid "
				+ "join hosts h on h.hostid = i.hostid")
				.where(Conditions.in("f.triggerid", rows.stream().map(ProblemRow::triggerId).distinct().toList()))
				.orderBy(Sort.asc("h.name")).build(),
				r -> Map.entry(r.getLong("triggerid"), r.getString("name")))
				.forEach(e -> hosts.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(e.getValue()));
		return rows.stream().map(p -> new ProblemRow(p.eventId(), p.triggerId(), p.name(), p.severity(), p.clock(),
				p.acknowledged(), p.suppressed(), List.copyOf(hosts.getOrDefault(p.triggerId(), Set.of())))).toList();
	}
}
