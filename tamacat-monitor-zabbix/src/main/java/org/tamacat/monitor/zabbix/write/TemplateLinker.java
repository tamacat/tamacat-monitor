package org.tamacat.monitor.zabbix.write;

import static org.tamacat.monitor.zabbix.write.HostGroupSets.ordered;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tamacat.dao.BindValue;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.IdentifierRules;
import org.tamacat.sql.SqlOperations;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.zabbix.ZabbixVersion;

/**
 * Linking templates to hosts and unlinking them (T-601), equal to what the Zabbix API writes
 * (golden-master tests on 6.0 / 7.0 / 8.0), for templates made of what tamacat-monitor writes elsewhere:
 * <ul>
 * <li>items of type Zabbix agent, Zabbix agent (active) and Zabbix trapper, with tags and preprocessing,
 * without value maps;</li>
 * <li>triggers on those items only (with tags and dependencies between them);</li>
 * <li>graphs of those items.</li>
 * </ul>
 * Templates with discovery rules, web scenarios, linked templates, other item types, value maps, or
 * triggers / graphs using items of other hosts are refused (DESIGN.md D-06: those stay in the Zabbix frontend).
 * <p>
 * Link: the template's items, triggers (expressions re-pointed to new functions) and graphs are copied to
 * the host with {@code templateid} set, as CHostGeneral::linkTemplatesObjects does. Unlink keeps the copies
 * as ordinary host objects ({@code templateid} cleared); unlink and clear deletes them like {@link Deletion}.
 */
public final class TemplateLinker {

	private static final Set<Integer> ITEM_TYPES = Set.of(0, 2, 7);
	private static final Pattern FUNCTION_REF = Pattern.compile("\\{(\\d+)\\}");

	/** The message key why tamacat-monitor cannot link this template, or empty. */
	public static Optional<String> unsupported(SqlOperations db, long templateId) {
		if (count(db, "select count(*) from hosts where hostid = ? and status = 3", templateId) == 0) {
			return Optional.of("error.template.notFound");
		}
		if (count(db, "select count(*) from items where hostid = ? and flags <> 0", templateId) > 0) {
			return Optional.of("error.template.discovery");
		}
		if (count(db, "select count(*) from httptest where hostid = ?", templateId) > 0) {
			return Optional.of("error.template.webScenarios");
		}
		if (count(db, "select count(*) from hosts_templates where hostid = ?", templateId) > 0) {
			return Optional.of("error.template.nested");
		}
		if (count(db, "select count(*) from items where hostid = ? and (type not in (0, 2, 7) or valuemapid is not null "
				+ "or master_itemid is not null)", templateId) > 0) {
			return Optional.of("error.template.itemTypes");
		}
		// triggers / graphs of the template that also use items of other hosts or templates
		if (count(db, "select count(*) from functions f join items i on i.itemid = f.itemid where i.hostid <> ? and f.triggerid in "
				+ "(select f2.triggerid from functions f2 join items i2 on i2.itemid = f2.itemid where i2.hostid = ?)", templateId,
				templateId) > 0
				|| count(db, "select count(*) from graphs_items gi join items i on i.itemid = gi.itemid where i.hostid <> ? and "
						+ "gi.graphid in (select gi2.graphid from graphs_items gi2 join items i2 on i2.itemid = gi2.itemid "
						+ "where i2.hostid = ?)", templateId, templateId) > 0) {
			return Optional.of("error.template.otherHosts");
		}
		if (count(db, "select count(*) from trigger_depends d join functions f on f.triggerid = d.triggerid_down "
				+ "join items i on i.itemid = f.itemid where i.hostid = ? and d.triggerid_up not in (select f2.triggerid from "
				+ "functions f2 join items i2 on i2.itemid = f2.itemid where i2.hostid = ?)", templateId, templateId) > 0) {
			return Optional.of("error.template.otherHosts");
		}
		return Optional.empty();
	}

	// ---- link ----

	public void link(WriteContext ctx, long hostId, String expectedHost, long templateId) {
		SqlOperations db = ctx.db();
		requireHost(db, hostId, expectedHost);
		unsupported(db, templateId).ifPresent(key -> {
			throw new ConflictException(key);
		});
		if (count(db, "select count(*) from hosts_templates where hostid = ? and templateid = ?", hostId, templateId) > 0) {
			throw new ConflictException("error.template.alreadyLinked");
		}
		boolean v60 = ctx.schema().version() == ZabbixVersion.V6_0;
		// ids are handed out in the order the API reads the template: 6.0 by id; 7.0+ through index (hostid, key_)
		List<Long> items = ids(db, "select itemid from items where hostid = ? order by " + (v60 ? "itemid" : "key_"), templateId);
		if (count(db, "select count(*) from items h join items t on t.key_ = h.key_ where h.hostid = ? and t.hostid = ?", hostId,
				templateId) > 0) {
			throw new ConflictException("error.template.itemConflict");
		}
		if (count(db, "select count(*) from graphs_items gi join items i on i.itemid = gi.itemid join graphs g on g.graphid = gi.graphid "
				+ "where i.hostid = ? and g.name in (select g2.name from graphs g2 join graphs_items gi2 on gi2.graphid = g2.graphid "
				+ "join items i2 on i2.itemid = gi2.itemid where i2.hostid = ?)", hostId, templateId) > 0) {
			throw new ConflictException("error.template.graphConflict");
		}
		Long agentInterface = db.queryOne(PreparedSql.of("select interfaceid from interface where hostid = ? and type = 1 and main = 1",
				hostId), r -> r.getLong(1)).orElse(null);
		if (agentInterface == null && count(db, "select count(*) from items where hostid = ? and type = 0", templateId) > 0) {
			throw new ConflictException("error.item.noAgentInterface");
		}
		Map<String, Object> link = ordered("hosttemplateid", ctx.nextId("hosts_templates", "hosttemplateid"), "hostid", hostId,
				"templateid", templateId);
		if (!v60) {
			link.put("link_type", 0); // 7.0+: 0 = linked manually
		}
		ctx.insert("hosts_templates", "hosttemplateid", link);
		if (ctx.schema().templateCache()) {
			ctx.insertWithoutId("host_template_cache", ordered("hostid", hostId, "link_hostid", templateId));
		}

		// items
		Map<Long, Long> itemMap = new LinkedHashMap<>();
		for (long t : items) {
			Map<String, Object> row = copy(db, "items", "itemid", t);
			long id = ctx.nextId("items", "itemid");
			itemMap.put(t, id);
			int type = ((Number) row.get("type")).intValue();
			row.put("itemid", id);
			row.put("hostid", hostId);
			row.put("templateid", t);
			row.put("interfaceid", type == 0 ? agentInterface : BindValue.ofNull(java.sql.Types.BIGINT));
			row.put("uuid", "");
			ctx.insert("items", "itemid", row);
		}
		for (var e : itemMap.entrySet()) {
			ctx.insert("item_rtdata", "itemid", ordered("itemid", e.getValue()));
			if (!v60) {
				String name = db.queryOne(PreparedSql.of("select name from items where itemid = ?", e.getKey()), r -> r.getString(1))
						.orElseThrow();
				ctx.insert("item_rtname", "itemid", ordered("itemid", e.getValue(), "name_resolved", name, "name_resolved_upper",
						name.toUpperCase(Locale.ROOT)));
			}
		}
		for (var e : itemMap.entrySet()) {
			for (long tag : ids(db, "select itemtagid from item_tag where itemid = ? order by itemtagid", e.getKey())) {
				Map<String, Object> row = copy(db, "item_tag", "itemtagid", tag);
				row.put("itemtagid", ctx.nextId("item_tag", "itemtagid"));
				row.put("itemid", e.getValue());
				ctx.insert("item_tag", "itemtagid", row);
			}
		}
		for (var e : itemMap.entrySet()) {
			for (long step : ids(db, "select item_preprocid from item_preproc where itemid = ? order by step", e.getKey())) {
				Map<String, Object> row = copy(db, "item_preproc", "item_preprocid", step);
				row.put("item_preprocid", ctx.nextId("item_preproc", "item_preprocid"));
				row.put("itemid", e.getValue());
				ctx.insert("item_preproc", "item_preprocid", row);
			}
		}
		if (ctx.schema().templateCache()) {
			for (long id : itemMap.values()) {
				ctx.insertWithoutId("item_template_cache", ordered("itemid", id, "link_hostid", templateId));
				ctx.insertWithoutId("item_template_cache", ordered("itemid", id, "link_hostid", hostId));
			}
		}

		// triggers (expressions point at the new functions)
		// triggers and graphs in the order they first appear through the items (key order), as the API reads them
		List<Long> triggers = firstAppearance(ids(db, "select f.triggerid from items i join functions f on f.itemid = i.itemid "
				+ "where i.hostid = ? order by " + (v60 ? "f.triggerid" : "i.key_, f.name, f.parameter, f.functionid"), templateId));
		Map<Long, Long> triggerMap = new LinkedHashMap<>();
		for (long t : triggers) {
			triggerMap.put(t, ctx.nextId("triggers", "triggerid"));
		}
		Map<Long, Long> functionMap = new HashMap<>();
		List<Map<String, Object>> functions = new ArrayList<>();
		for (long t : triggers) {
			for (long f : ids(db, "select functionid from functions where triggerid = ? order by functionid", t)) {
				Map<String, Object> row = copy(db, "functions", "functionid", f);
				long id = ctx.nextId("functions", "functionid");
				functionMap.put(f, id);
				row.put("functionid", id);
				row.put("itemid", itemMap.get(((Number) row.get("itemid")).longValue()));
				row.put("triggerid", triggerMap.get(t));
				functions.add(row);
			}
		}
		for (long t : triggers) {
			Map<String, Object> row = copy(db, "triggers", "triggerid", t);
			row.put("triggerid", triggerMap.get(t));
			row.put("templateid", t);
			row.put("uuid", "");
			row.put("expression", repoint((String) row.get("expression"), functionMap));
			row.put("recovery_expression", repoint((String) row.get("recovery_expression"), functionMap));
			ctx.insert("triggers", "triggerid", row);
		}
		for (Map<String, Object> f : functions) {
			ctx.insert("functions", "functionid", f);
		}
		for (long t : triggers) {
			for (long tag : ids(db, "select triggertagid from trigger_tag where triggerid = ? order by triggertagid", t)) {
				Map<String, Object> row = copy(db, "trigger_tag", "triggertagid", tag);
				row.put("triggertagid", ctx.nextId("trigger_tag", "triggertagid"));
				row.put("triggerid", triggerMap.get(t));
				ctx.insert("trigger_tag", "triggertagid", row);
			}
		}
		if (ctx.schema().version() == ZabbixVersion.V8_0) {
			for (long id : triggerMap.values()) {
				ctx.insert("trigger_rtdata", "triggerid", ordered("triggerid", id));
			}
		}

		// graphs
		for (long g : firstAppearance(ids(db, "select gi.graphid from items i join graphs_items gi on gi.itemid = i.itemid "
				+ "where i.hostid = ? order by " + (v60 ? "gi.graphid" : "i.key_, gi.graphid"), templateId))) {
			Map<String, Object> row = copy(db, "graphs", "graphid", g);
			long id = ctx.nextId("graphs", "graphid");
			row.put("graphid", id);
			row.put("templateid", g);
			row.put("uuid", "");
			for (String side : List.of("ymin_itemid", "ymax_itemid")) {
				if (row.get(side) instanceof Number n) {
					row.put(side, itemMap.get(n.longValue()));
				}
			}
			ctx.insert("graphs", "graphid", row);
			for (long gi : ids(db, "select gitemid from graphs_items where graphid = ? order by gitemid", g)) {
				Map<String, Object> r = copy(db, "graphs_items", "gitemid", gi);
				r.put("gitemid", ctx.nextId("graphs_items", "gitemid"));
				r.put("graphid", id);
				r.put("itemid", itemMap.get(((Number) r.get("itemid")).longValue()));
				ctx.insert("graphs_items", "gitemid", r);
			}
		}

		// dependencies between the template's triggers
		for (long t : triggers) {
			for (long up : ids(db, "select triggerid_up from trigger_depends where triggerid_down = ? order by triggerdepid", t)) {
				ctx.insert("trigger_depends", "triggerdepid", ordered("triggerdepid", ctx.nextId("trigger_depends", "triggerdepid"),
						"triggerid_down", triggerMap.get(t), "triggerid_up", triggerMap.get(up)));
			}
		}
	}

	// ---- unlink ----

	/** Unlinks the template; {@code clear} also deletes its items, triggers and graphs on the host. */
	public void unlink(WriteContext ctx, long hostId, String expectedHost, long templateId, boolean clear) {
		SqlOperations db = ctx.db();
		requireHost(db, hostId, expectedHost);
		long link = db.queryOne(PreparedSql.of("select hosttemplateid from hosts_templates where hostid = ? and templateid = ?",
				hostId, templateId), r -> r.getLong(1)).orElseThrow(() -> new NotFoundException("template link"));
		List<Long> items = ids(db, "select h.itemid from items h join items t on t.itemid = h.templateid where h.hostid = ? "
				+ "and t.hostid = ? order by h.itemid", hostId, templateId);
		if (clear) {
			Deletion.itemsBlocker(db, ctx.schema(), items, true).ifPresent(key -> {
				throw new ConflictException(key);
			});
			Deletion.deleteItems(ctx, items);
		} else {
			List<Long> triggers = ids(db, "select h.triggerid from triggers h join functions f on f.triggerid = h.templateid "
					+ "join items t on t.itemid = f.itemid where t.hostid = ? and h.triggerid in (select f2.triggerid from functions f2 "
					+ "join items i2 on i2.itemid = f2.itemid where i2.hostid = ?) group by h.triggerid order by h.triggerid",
					templateId, hostId);
			List<Long> graphs = ids(db, "select distinct h.graphid from graphs h join graphs_items gi on gi.graphid = h.templateid "
					+ "join items t on t.itemid = gi.itemid where t.hostid = ? and h.graphid in (select gi2.graphid from graphs_items gi2 "
					+ "join items i2 on i2.itemid = gi2.itemid where i2.hostid = ?) order by h.graphid", templateId, hostId);
			for (long g : graphs) {
				ctx.update("graphs", "graphid", g, Map.of(), Map.of("templateid", BindValue.ofNull(java.sql.Types.BIGINT)));
			}
			for (long i : items) {
				ctx.update("items", "itemid", i, Map.of(), Map.of("templateid", BindValue.ofNull(java.sql.Types.BIGINT)));
				if (ctx.schema().templateCache()) {
					ctx.delete("item_template_cache", "itemid", i, Map.of("link_hostid", templateId));
				}
			}
			for (long t : triggers) {
				ctx.update("triggers", "triggerid", t, Map.of(), Map.of("templateid", BindValue.ofNull(java.sql.Types.BIGINT)));
			}
		}
		if (ctx.schema().templateCache()) {
			ctx.delete("host_template_cache", "hostid", hostId, Map.of("link_hostid", templateId));
		}
		ctx.delete("hosts_templates", "hosttemplateid", link, Map.of("hostid", hostId, "templateid", templateId));
	}

	// ---- parts ----

	private static void requireHost(SqlOperations db, long hostId, String expectedHost) {
		String host = db.queryOne(PreparedSql.of("select host from hosts where hostid = ? and status in (0, 1) and flags = 0", hostId),
				r -> r.getString(1)).orElseThrow(() -> new NotFoundException("host " + hostId));
		if (!host.equals(expectedHost)) {
			throw new ConflictException("error.write.conflict");
		}
	}

	/** {123} -> {new id} for every function of the trigger. */
	static String repoint(String expression, Map<Long, Long> functionMap) {
		if (expression == null || expression.isEmpty()) {
			return expression == null ? "" : expression;
		}
		Matcher m = FUNCTION_REF.matcher(expression);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			Long id = functionMap.get(Long.parseLong(m.group(1)));
			m.appendReplacement(sb, Matcher.quoteReplacement(id == null ? m.group() : "{" + id + "}"));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	/** All columns of one row, as bind-ready values (null keeps its SQL type). */
	static Map<String, Object> copy(SqlOperations db, String table, String idColumn, long id) {
		String t = IdentifierRules.validate(table);
		String c = IdentifierRules.validate(idColumn);
		return db.queryOne(PreparedSql.of("select * from " + t + " where " + c + " = ?", id), r -> {
			ResultSet rs = r.resultSet();
			ResultSetMetaData md = rs.getMetaData();
			Map<String, Object> row = new LinkedHashMap<>();
			for (int i = 1; i <= md.getColumnCount(); i++) {
				row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), value(rs, i, md.getColumnType(i)));
			}
			return row;
		}).orElseThrow(() -> new NotFoundException(table + " " + id));
	}

	private static Object value(ResultSet rs, int i, int sqlType) throws SQLException {
		Object v = rs.getObject(i);
		return switch (v) {
			case null -> BindValue.ofNull(sqlType);
			case BigInteger b -> b.longValueExact();
			case Double d -> BigDecimal.valueOf(d);
			case Float f -> BigDecimal.valueOf(f.doubleValue());
			case Short s -> s.intValue();
			case Byte b -> b.intValue();
			default -> v;
		};
	}

	private static List<Long> firstAppearance(List<Long> ids) {
		return List.copyOf(new java.util.LinkedHashSet<>(ids));
	}

	private static List<Long> ids(SqlOperations db, String sql, Object... args) {
		return db.queryList(PreparedSql.of(sql, args), r -> r.getLong(1));
	}

	private static int count(SqlOperations db, String sql, Object... args) {
		return db.queryOne(PreparedSql.of(sql, args), r -> r.getInt(1)).orElse(0);
	}
}
