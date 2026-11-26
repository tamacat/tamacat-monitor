package org.tamacat.monitor.zabbix;

import java.util.Map;

import org.tamacat.dao.Conditions;
import org.tamacat.dao.Param;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.SqlOperations;

/**
 * SQL differences between Zabbix versions (DESIGN.md 4.2). Fragments returned here are constant
 * SQL text (no user input); values always go through {@link Param}.
 */
public sealed interface ZabbixSchema permits ZabbixSchema.Schema60, ZabbixSchema.Schema70, ZabbixSchema.Schema80 {

	ZabbixVersion version();

	/** Condition selecting host groups (not template groups) in {@code hstgrp} aliased as {@code alias}. */
	Param hostGroupsOnly(String alias);

	/** "left join ..." adding the proxy of host alias {@code h} as alias {@code px}. */
	String proxyJoin();

	/** Column with the proxy name (alias {@code px}). */
	String proxyName();

	/** History table of an item value type, or null when the type has no readable history. */
	default String historyTable(int valueType) {
		return switch (valueType) {
			case 0 -> "history";
			case 1 -> "history_str";
			case 2 -> "history_log";
			case 3 -> "history_uint";
			case 4 -> "history_text";
			default -> null; // 5 = binary (7.0+): not shown as text
		};
	}

	/** Housekeeping modes {history, trends}: 1 = the housekeeper deletes history / trends of deleted items. */
	default int[] housekeepingModes(SqlOperations db) {
		return db.queryOne(PreparedSql.of("select hk_history_mode, hk_trends_mode from config"),
				r -> new int[] { r.getInt(1), r.getInt(2) }).orElse(new int[] { 1, 1 });
	}

	/**
	 * True when the frontend writes the housekeeper rows of deleted items and triggers (6.0, 7.0). 8.0 does
	 * it with DB triggers (items_housekeeping, triggers_housekeeping).
	 */
	default boolean frontendHousekeeper() {
		return true;
	}

	/** Query counting whether the host group (one placeholder) is the group of discovered hosts. */
	default String discoveryGroupReference() {
		return "select count(*) from config where discovery_groupid = ?";
	}

	/** TEXT columns without a DB default that a new host row must set (MySQL). */
	default Map<String, Object> newHostColumns() {
		return Map.of();
	}

	/** TEXT columns without a DB default that a new item row must set (MySQL). */
	default Map<String, Object> newItemColumns() {
		return Map.of();
	}

	/**
	 * True when hosts and items keep a template cache (8.0): host_template_cache (host, itself and its
	 * templates), item_template_cache (item, its host and the templates it comes from).
	 */
	default boolean templateCache() {
		return false;
	}

	/** Allowed hosts the API sets on new trapper items, or null to keep the DB default (8.0: a global macro). */
	default String newTrapperHosts() {
		return null;
	}

	/** True when map links may reference items (8.0, without ON DELETE CASCADE). */
	default boolean mapLinksReferenceItems() {
		return false;
	}

	static ZabbixSchema of(ZabbixVersion v) {
		return switch (v) {
			case V6_0 -> new Schema60();
			case V7_0 -> new Schema70(v);
			case V8_0 -> new Schema80();
		};
	}

	/**
	 * 6.0: host groups and template groups share hstgrp without a type column;
	 * proxies are rows of {@code hosts} referenced by {@code hosts.proxy_hostid}.
	 */
	record Schema60() implements ZabbixSchema {
		@Override
		public ZabbixVersion version() {
			return ZabbixVersion.V6_0;
		}

		@Override
		public Param hostGroupsOnly(String alias) {
			return null;
		}

		@Override
		public String proxyJoin() {
			return "left join hosts px on px.hostid = h.proxy_hostid";
		}

		@Override
		public String proxyName() {
			return "px.host";
		}
	}

	/**
	 * 7.0: hstgrp.type 0 = host group, 1 = template group (since 6.2);
	 * proxies are in the {@code proxy} table referenced by {@code hosts.proxyid}.
	 */
	record Schema70(ZabbixVersion version) implements ZabbixSchema {
		@Override
		public Param hostGroupsOnly(String alias) {
			return Conditions.eq(alias + ".type", 0);
		}

		@Override
		public String proxyJoin() {
			return "left join proxy px on px.proxyid = h.proxyid";
		}

		@Override
		public String proxyName() {
			return "px.name";
		}
	}

	/**
	 * 8.0 (verified against 8.0.0, T-501): as 7.0, and
	 * <ul>
	 * <li>the {@code config} table is replaced by {@code settings} (name / value_*; only values that differ
	 * from the default are stored);</li>
	 * <li>housekeeper rows of deleted items / triggers are written by DB triggers;</li>
	 * <li>new TEXT columns hosts.readme and items.query;</li>
	 * <li>runtime trigger columns (value, state, lastchange, error) moved to trigger_rtdata;</li>
	 * <li>map links can show an item value (sysmaps_links.itemid, no cascade);</li>
	 * <li>template caches host_template_cache / item_template_cache.</li>
	 * </ul>
	 */
	record Schema80() implements ZabbixSchema {
		@Override
		public ZabbixVersion version() {
			return ZabbixVersion.V8_0;
		}

		@Override
		public Param hostGroupsOnly(String alias) {
			return Conditions.eq(alias + ".type", 0);
		}

		@Override
		public String proxyJoin() {
			return "left join proxy px on px.proxyid = h.proxyid";
		}

		@Override
		public String proxyName() {
			return "px.name";
		}

		@Override
		public int[] housekeepingModes(SqlOperations db) {
			return new int[] { setting(db, "hk_history_mode", 1), setting(db, "hk_trends_mode", 1) };
		}

		@Override
		public boolean frontendHousekeeper() {
			return false;
		}

		@Override
		public String discoveryGroupReference() {
			return "select count(*) from settings where name = 'discovery_groupid' and value_hostgroupid = ?";
		}

		@Override
		public Map<String, Object> newHostColumns() {
			return Map.of("readme", "");
		}

		@Override
		public Map<String, Object> newItemColumns() {
			return Map.of("query", "{}"); // the API writes an empty JSON object
		}

		@Override
		public String newTrapperHosts() {
			return "{$TRAPPER.ALLOWED_HOSTS}";
		}

		@Override
		public boolean templateCache() {
			return true;
		}

		@Override
		public boolean mapLinksReferenceItems() {
			return true;
		}

		/** An integer setting; the default when no row is stored (CSettingsSchema). */
		static int setting(SqlOperations db, String name, int defaultValue) {
			return db.queryOne(PreparedSql.of("select value_int from settings where name = ?", name), r -> r.getInt(1))
					.orElse(defaultValue);
		}
	}
}
