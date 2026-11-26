package org.tamacat.monitor.zabbix.write;

import static org.tamacat.monitor.zabbix.write.HostGroupSets.ordered;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.tamacat.dao.BindValue;
import org.tamacat.dao.Conditions;
import org.tamacat.dao.Param;
import org.tamacat.dao.PreparedSql;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.dao.Sort;
import org.tamacat.sql.SqlOperations;
import org.tamacat.monitor.zabbix.ZabbixVersion;

/**
 * Deleting items with what depends on them (T-408), as the Zabbix API does (CItemManager::delete in
 * 6.0, CItem::deleteForce in 7.0):
 * <ol>
 * <li>graphs that would be left without items (and the "latest graph" profile rows of them);</li>
 * <li>y axis of other graphs that use a deleted item as min / max: back to "calculated";</li>
 * <li>"favorite graph" profile rows of the items;</li>
 * <li>triggers using the items (functions, tags, dependencies), with a housekeeper row for their events;</li>
 * <li>housekeeper rows for the events, history and trends of each item (by the housekeeping settings);</li>
 * <li>preprocessing steps, tags and the items. Runtime rows (item_rtdata, item_rtname, ...) are removed by
 * ON DELETE CASCADE, as with the API.</li>
 * </ol>
 * tamacat-monitor refuses where Zabbix 6.0 and 7.0 differ or other configuration would change: triggers used in action
 * conditions (6.0 disables the action, 7.0 refuses) or on maps.
 */
final class Deletion {

	/** History tables in the order of the API (6.0: CItemManager, 7.0: CHistoryManager::getTableName). */
	private static final List<String> HISTORY_60 = List.of("history", "history_str", "history_uint", "history_log", "history_text");
	private static final List<String> HISTORY_70 = List.of("history_log", "history_text", "history_str", "history", "history_uint",
			"history_bin");

	private Deletion() {
	}

	/** Triggers using any of the items. */
	static List<Long> triggersOf(SqlOperations db, Collection<Long> itemIds) {
		if (itemIds.isEmpty()) {
			return List.of();
		}
		return db.queryList(SqlBuilder.of("select distinct f.triggerid from functions f").where(Conditions.in("f.itemid", List.copyOf(itemIds)))
				.orderBy(Sort.asc("f.triggerid")).build(), r -> r.getLong(1));
	}

	/**
	 * The message key why these items cannot be deleted, or empty.
	 *
	 * @param withMasters true when every item of the host goes (dependent items go with their master)
	 */
	static Optional<String> itemsBlocker(SqlOperations db, org.tamacat.monitor.zabbix.ZabbixSchema schema, Collection<Long> itemIds,
			boolean withMasters) {
		if (itemIds.isEmpty()) {
			return Optional.empty();
		}
		List<Long> ids = List.copyOf(itemIds);
		if (schema.mapLinksReferenceItems() && count(db, SqlBuilder.of("select count(*) from sysmaps_links")
				.where(Conditions.in("itemid", ids)).build()) > 0) {
			return Optional.of("error.delete.itemInMap");
		}
		if (!withMasters && count(db, SqlBuilder.of("select count(*) from items").where(Conditions.in("master_itemid", ids),
				Conditions.notIn("itemid", ids)).build()) > 0) {
			return Optional.of("error.delete.dependentItems");
		}
		List<Long> triggers = triggersOf(db, ids);
		if (!triggers.isEmpty()) {
			List<String> values = triggers.stream().map(String::valueOf).toList();
			if (count(db, SqlBuilder.of("select count(*) from conditions").where(Param.of("conditiontype = 2"),
					Conditions.in("value", values)).build()) > 0) {
				return Optional.of("error.delete.triggerInAction");
			}
			if (count(db, SqlBuilder.of("select count(*) from sysmap_element_trigger").where(Conditions.in("triggerid", triggers))
					.build()) > 0) {
				return Optional.of("error.delete.triggerInMap");
			}
		}
		return Optional.empty();
	}

	/** Deletes the items (checked with {@link #itemsBlocker} before) and what depends on them. */
	static void deleteItems(WriteContext ctx, List<Long> itemIds) {
		if (itemIds.isEmpty()) {
			return;
		}
		SqlOperations db = ctx.db();
		// 1. graphs left without items
		List<Long> graphs = db.queryList(SqlBuilder.of("select distinct gi.graphid from graphs_items gi")
				.where(Conditions.in("gi.itemid", itemIds), Param.of("not exists (select null from graphs_items gii "
						+ "where gii.graphid = gi.graphid and " + notIn("gii.itemid", itemIds.size()) + ")", ids(itemIds)))
				.orderBy(Sort.asc("gi.graphid")).build(), r -> r.getLong(1));
		for (long g : graphs) {
			deleteRows(ctx, "profiles", "profileid", SqlBuilder.of("select profileid from profiles")
					.where(Param.of("idx = 'web.latest.graphid'"), Conditions.eq("value_id", g)).build());
			deleteRows(ctx, "graphs_items", "gitemid", SqlBuilder.of("select gitemid from graphs_items")
					.where(Conditions.eq("graphid", g)).build());
			ctx.delete("graphs", "graphid", g, Map.of());
		}
		// 2. y axis of other graphs
		for (String side : List.of("ymin", "ymax")) {
			for (long g : db.queryList(SqlBuilder.of("select graphid from graphs").where(Conditions.in(side + "_itemid", itemIds))
					.orderBy(Sort.asc("graphid")).build(), r -> r.getLong(1))) {
				Map<String, Object> ch = new LinkedHashMap<>();
				ch.put(side + "_type", 0);
				ch.put(side + "_itemid", BindValue.ofNull(Types.BIGINT));
				ctx.update("graphs", "graphid", g, Map.of(), ch);
			}
		}
		// 3. favorite graphs of the items
		deleteRows(ctx, "profiles", "profileid", SqlBuilder.of("select profileid from profiles")
				.where(Param.of("idx = 'web.favorite.graphids' and source = 'itemid'"), Conditions.in("value_id", itemIds)).build());
		// 4. triggers
		List<Long> triggers = triggersOf(db, itemIds);
		for (long t : triggers) {
			deleteRows(ctx, "functions", "functionid", SqlBuilder.of("select functionid from functions")
					.where(Conditions.eq("triggerid", t)).build());
			deleteRows(ctx, "trigger_tag", "triggertagid", SqlBuilder.of("select triggertagid from trigger_tag")
					.where(Conditions.eq("triggerid", t)).build());
			deleteRows(ctx, "trigger_depends", "triggerdepid", SqlBuilder.of("select triggerdepid from trigger_depends")
					.where(Conditions.eq("triggerid_down", t).or(Conditions.eq("triggerid_up", t))).build());
			ctx.delete("triggers", "triggerid", t, Map.of());
		}
		if (ctx.schema().frontendHousekeeper()) {
			for (long t : triggers) {
				housekeeper(ctx, "events", "triggerid", t);
			}
		}
		// 5. housekeeper rows of the items (8.0: DB triggers write them)
		if (ctx.schema().frontendHousekeeper()) {
			boolean v60 = ctx.schema().version() == ZabbixVersion.V6_0;
			int[] hk = ctx.schema().housekeepingModes(db);
			List<String> tables = new ArrayList<>(List.of("events"));
			if (hk[0] != 0) {
				tables.addAll(v60 ? HISTORY_60 : HISTORY_70);
			}
			if (hk[1] != 0) {
				tables.addAll(List.of("trends", "trends_uint"));
			}
			for (long i : itemIds) {
				for (String table : tables) {
					housekeeper(ctx, table, "itemid", i);
				}
			}
		}
		// 6. items, dependent items before their masters
		for (long i : itemIds) {
			deleteRows(ctx, "item_preproc", "item_preprocid", SqlBuilder.of("select item_preprocid from item_preproc")
					.where(Conditions.eq("itemid", i)).build());
			deleteRows(ctx, "item_tag", "itemtagid", SqlBuilder.of("select itemtagid from item_tag").where(Conditions.eq("itemid", i))
					.build());
		}
		for (long i : dependentsFirst(db, itemIds)) {
			ctx.delete("items", "itemid", i, Map.of());
		}
	}

	private static List<Long> dependentsFirst(SqlOperations db, List<Long> itemIds) {
		Map<Long, Long> master = new LinkedHashMap<>();
		db.queryList(SqlBuilder.of("select itemid, master_itemid from items").where(Conditions.in("itemid", itemIds)).build(),
				r -> new Long[] { r.getLong(1), r.getNullableLong(2) }).forEach(v -> master.put(v[0], v[1]));
		List<Long> out = new ArrayList<>();
		Set<Long> left = new LinkedHashSet<>(itemIds);
		while (!left.isEmpty()) {
			for (long i : List.copyOf(left)) {
				boolean isMaster = left.stream().anyMatch(o -> o != i && Long.valueOf(i).equals(master.get(o)));
				if (!isMaster) {
					out.add(i);
					left.remove(i);
				}
			}
		}
		return out;
	}

	static void housekeeper(WriteContext ctx, String table, String field, long value) {
		ctx.insert("housekeeper", "housekeeperid", ordered("housekeeperid", ctx.nextId("housekeeper", "housekeeperid"),
				"tablename", table, "field", field, "value", value));
	}

	/** Deletes each row the select (of the id column) returns. */
	static void deleteRows(WriteContext ctx, String table, String idColumn, PreparedSql select) {
		for (long id : ctx.db().queryList(select, r -> r.getLong(1))) {
			ctx.delete(table, idColumn, id, Map.of());
		}
	}

	private static int count(SqlOperations db, PreparedSql sql) {
		return db.queryOne(sql, r -> r.getInt(1)).orElse(0);
	}

	private static String notIn(String column, int n) {
		return column + " not in (" + String.join(", ", java.util.Collections.nCopies(n, "?")) + ")";
	}

	private static Object[] ids(List<Long> ids) {
		return ids.toArray();
	}
}
