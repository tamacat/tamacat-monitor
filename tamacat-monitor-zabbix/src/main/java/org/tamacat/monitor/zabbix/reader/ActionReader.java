package org.tamacat.monitor.zabbix.reader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.tamacat.dao.Conditions;
import org.tamacat.dao.Page;
import org.tamacat.dao.Param;
import org.tamacat.dao.PreparedSql;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.dao.Sort;
import org.tamacat.sql.LikeMode;
import org.tamacat.sql.SqlOperations;

/**
 * Reads actions. Actions are global in Zabbix, so callers allow this for server administrators
 * only (D-09).
 */
public final class ActionReader {

	/**
	 * @param eventSource 0 trigger, 1 discovery, 2 autoregistration, 3 internal, 4 service
	 * @param status      0 enabled, 1 disabled
	 */
	public record ActionSummary(long actionId, String name, int eventSource, int status, String escPeriod,
			int conditionCount, int operationCount) {
	}

	/**
	 * @param conditionType Zabbix condition type (0 host group, 1 host, 2 trigger, 3 event name, 4 severity, ...)
	 * @param operator      Zabbix operator (0 equals, 1 not equals, 2 contains, ...)
	 * @param valueName     resolved name for host group / host / trigger / template ids, else null
	 */
	public record Condition(int conditionType, int operator, String value, String value2, String valueName) {
	}

	/**
	 * @param operationType 0 send message, 1 remote command, ... (see Zabbix)
	 * @param recovery      0 problem, 1 recovery, 2 update
	 */
	public record Operation(long operationId, int operationType, int recovery, int stepFrom, int stepTo, String escPeriod,
			String mediaType, List<String> userGroups, List<String> users, String subject) {
	}

	public record ActionDetail(ActionSummary summary, int evalType, String formula, List<Condition> conditions,
			List<Operation> operations) {
	}

	/** A choice for the action form (user group, user or media type). */
	public record Choice(long id, String name) {
	}

	public record Choices(List<Choice> userGroups, List<Choice> users, List<Choice> mediaTypes) {
	}

	/** Recipients and media types for the action form (reference only, D-07). */
	public Choices choices(SqlOperations db) {
		return new Choices(
				db.queryList(PreparedSql.of("select usrgrpid, name from usrgrp order by name"),
						r -> new Choice(r.getLong(1), r.getString(2))),
				db.queryList(PreparedSql.of("select userid, username, name, surname from users order by username"), r -> {
					String full = (r.getString("name") + " " + r.getString("surname")).strip();
					return new Choice(r.getLong("userid"), full.isEmpty() ? r.getString("username")
							: r.getString("username") + " (" + full + ")");
				}),
				db.queryList(PreparedSql.of("select mediatypeid, name from media_type order by name"),
						r -> new Choice(r.getLong(1), r.getString(2))));
	}

	public List<ActionSummary> search(SqlOperations db, Integer eventSource, String text, Page page) {
		List<Param> where = new ArrayList<>();
		if (eventSource != null) {
			where.add(Conditions.eq("a.eventsource", eventSource));
		}
		if (text != null && !text.isBlank()) {
			where.add(Conditions.likeIgnoreCase("a.name", text.strip(), LikeMode.CONTAINS));
		}
		var sql = SqlBuilder.of(select() + " from actions a").where(where)
				.orderBy(Sort.asc("a.eventsource").then("a.name", Sort.Order.ASC)).build(db.dialect(), page);
		return db.queryList(sql, ActionReader::summary);
	}

	public Optional<ActionDetail> get(SqlOperations db, long actionId) {
		Optional<ActionSummary> s = db.queryOne(SqlBuilder.of(select() + " from actions a")
				.where(Conditions.eq("a.actionid", actionId)).build(), ActionReader::summary);
		if (s.isEmpty()) {
			return Optional.empty();
		}
		var eval = db.queryOne(PreparedSql.of("select evaltype, formula from actions where actionid = ?", actionId),
				r -> Map.entry(r.getInt("evaltype"), r.getString("formula"))).orElseThrow();
		List<Condition> conditions = db.queryList(PreparedSql.of("select conditiontype, operator, value, value2 from conditions "
				+ "where actionid = ? order by conditionid", actionId),
				r -> new Condition(r.getInt("conditiontype"), r.getInt("operator"), r.getString("value"),
						r.getString("value2"), null));
		conditions = conditions.stream().map(c -> new Condition(c.conditionType(), c.operator(), c.value(), c.value2(),
				resolve(db, c))).toList();
		return Optional.of(new ActionDetail(s.get(), eval.getKey(), eval.getValue(), conditions, operations(db, actionId)));
	}

	private static String select() {
		return "select a.actionid, a.name, a.eventsource, a.status, a.esc_period, "
				+ "(select count(*) from conditions c where c.actionid = a.actionid) as condition_count, "
				+ "(select count(*) from operations o where o.actionid = a.actionid) as operation_count";
	}

	private static ActionSummary summary(org.tamacat.sql.Row r) throws java.sql.SQLException {
		return new ActionSummary(r.getLong("actionid"), r.getString("name"), r.getInt("eventsource"), r.getInt("status"),
				r.getString("esc_period"), r.getInt("condition_count"), r.getInt("operation_count"));
	}

	private static List<Operation> operations(SqlOperations db, long actionId) {
		record Op(long id, int type, int recovery, int from, int to, String period, String media, String subject) {
		}
		List<Op> ops = db.queryList(PreparedSql.of("select o.operationid, o.operationtype, o.recovery, o.esc_step_from, "
				+ "o.esc_step_to, o.esc_period, mt.name as media_type, m.subject from operations o "
				+ "left join opmessage m on m.operationid = o.operationid left join media_type mt on mt.mediatypeid = m.mediatypeid "
				+ "where o.actionid = ? order by o.recovery, o.esc_step_from, o.operationid", actionId),
				r -> new Op(r.getLong("operationid"), r.getInt("operationtype"), r.getInt("recovery"),
						r.getInt("esc_step_from"), r.getInt("esc_step_to"), r.getString("esc_period"),
						r.getString("media_type"), r.getString("subject")));
		if (ops.isEmpty()) {
			return List.of();
		}
		List<Long> ids = ops.stream().map(Op::id).toList();
		Map<Long, List<String>> groups = new HashMap<>();
		db.queryList(SqlBuilder.of("select og.operationid, g.name from opmessage_grp og join usrgrp g on g.usrgrpid = og.usrgrpid")
				.where(Conditions.in("og.operationid", ids)).orderBy(Sort.asc("g.name")).build(),
				r -> Map.entry(r.getLong("operationid"), r.getString("name")))
				.forEach(e -> groups.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
		Map<Long, List<String>> users = new HashMap<>();
		db.queryList(SqlBuilder.of("select ou.operationid, u.username from opmessage_usr ou join users u on u.userid = ou.userid")
				.where(Conditions.in("ou.operationid", ids)).orderBy(Sort.asc("u.username")).build(),
				r -> Map.entry(r.getLong("operationid"), r.getString("username")))
				.forEach(e -> users.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
		return ops.stream().map(o -> new Operation(o.id(), o.type(), o.recovery(), o.from(), o.to(), o.period(), o.media(),
				groups.getOrDefault(o.id(), List.of()), users.getOrDefault(o.id(), List.of()), o.subject())).toList();
	}

	/** Name for id-valued conditions: 0 host group, 1 host, 2 trigger, 13 template. */
	private static String resolve(SqlOperations db, Condition c) {
		long id;
		try {
			id = Long.parseLong(c.value());
		} catch (NumberFormatException e) {
			return null;
		}
		PreparedSql sql = switch (c.conditionType()) {
			case 0 -> PreparedSql.of("select name from hstgrp where groupid = ?", id);
			case 1, 13 -> PreparedSql.of("select name from hosts where hostid = ?", id);
			case 2 -> PreparedSql.of("select description as name from triggers where triggerid = ?", id);
			default -> null;
		};
		return sql == null ? null : db.queryOne(sql, r -> r.getString("name")).orElse(null);
	}
}
