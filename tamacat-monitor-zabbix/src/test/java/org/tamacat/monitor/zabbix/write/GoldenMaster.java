package org.tamacat.monitor.zabbix.write;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Database;

/**
 * Golden-master comparison (T-401): records which rows of a Zabbix DB an operation changes, so the
 * changes made through the Zabbix API and through tamacat-monitor's writers can be compared.
 * <ul>
 * <li>Every table is check-summed before and after; changed tables are compared row by row.</li>
 * <li>Tables and columns the Zabbix server changes all the time (history, events, availability, ...)
 * are ignored, and so is the Zabbix audit log, which tamacat-monitor does not write (D-08).</li>
 * <li>{@code ids} is not compared structurally (the first allocation inserts its row, later ones update
 * it); tests check its consistency separately.</li>
 * <li>Normalization: new primary keys become #1, #2, ... in order; 32-hex uuids become &lt;uuid&gt;.</li>
 * </ul>
 */
final class GoldenMaster {

	private static final Pattern VOLATILE_TABLE = Pattern.compile(
			"history.*|trends.*|auditlog|events|event_.*|problem.*|alerts|escalations|host_rtdata|sessions|"
					+ "ha_node|task.*|housekeeper|changelog|proxy_.*|lld_.*|item_discovery|host_discovery|ids|"
					+ "globalvars|config_autoreg_tls|acknowledges|dbversion|module|item_preproc_.*|users|hgset_.*_tmp");
	/** Columns updated by the Zabbix server (availability, lastcheck, ...). */
	private static final Set<String> VOLATILE_COLUMNS = Set.of("interface.available", "interface.error",
			"interface.errors_from", "interface.disable_until", "hosts.maintenance_from", "hosts.maintenance_status",
			"hosts.maintenance_type", "hosts.maintenanceid", "hosts.lastaccess", "triggers.value", "triggers.lastchange",
			"triggers.error", "triggers.state", "trigger_rtdata.value", "trigger_rtdata.state", "trigger_rtdata.lastchange", "trigger_rtdata.error",
			// item_rtdata rows are created by writers, their values are maintained by the Zabbix server
			"item_rtdata.lastlogsize", "item_rtdata.state", "item_rtdata.mtime", "item_rtdata.error");
	private static final Pattern UUID32 = Pattern.compile("[0-9a-f]{32}");
	/** Id columns: names ending in "id", or "id_" and a suffix (trigger_depends.triggerid_down). */
	private static final Pattern ID_COL = Pattern.compile("(\\w*id(?:_[a-z]+)?)=(\\d+)");
	private static final Pattern ID_COL_CHANGE = Pattern.compile("(\\w*id(?:_[a-z]+)?)=(\\d+)(?: -> (\\d+))?");
	private static final Pattern DIGITS = Pattern.compile("\\d+");
	private static final Pattern FUNCTION_REF = Pattern.compile("\\{(\\d+)\\}");
	private static final Pattern UPDATE_KEY = Pattern.compile("^update (\\d+)\\|");

	/** Table -> primary key -> column -> value. */
	record Snapshot(Map<String, Long> checksums, Map<String, Map<String, Map<String, String>>> rows) {
	}

	private final Database db;

	GoldenMaster(Database db) {
		this.db = db;
	}

	Map<String, Long> checksums() {
		List<String> tables = db.queryList(PreparedSql.of("select table_name from information_schema.tables "
				+ "where table_schema = database() and table_type = 'BASE TABLE'"), r -> r.getString(1));
		Map<String, Long> sums = new TreeMap<>();
		for (String t : tables) {
			if (!VOLATILE_TABLE.matcher(t).matches()) {
				sums.put(t, db.queryOne(PreparedSql.of("checksum table " + t), r -> r.getLong(2)).orElse(0L));
			}
		}
		return sums;
	}

	Map<String, Map<String, String>> rows(String table) {
		List<String> pk = db.queryList(PreparedSql.of("select column_name from information_schema.key_column_usage "
				+ "where table_schema = database() and table_name = ? and constraint_name = 'PRIMARY' order by ordinal_position",
				table), r -> r.getString(1));
		return db.queryWith(PreparedSql.of("select * from " + table), rs -> {
			ResultSetMetaData md = rs.getMetaData();
			Map<String, Map<String, String>> out = new HashMap<>();
			while (rs.next()) {
				Map<String, String> row = new TreeMap<>();
				for (int i = 1; i <= md.getColumnCount(); i++) {
					String col = md.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT);
					if (!VOLATILE_COLUMNS.contains(table + "." + col)) {
						row.put(col, rs.getString(i));
					}
				}
				StringBuilder key = new StringBuilder();
				for (String c : pk) {
					key.append(row.get(c.toLowerCase(java.util.Locale.ROOT))).append('|');
				}
				out.put(key.toString(), row);
			}
			return out;
		});
	}

	/** Runs {@code op} and returns the normalized changes: table -> sorted change descriptions. */
	Map<String, List<String>> capture(Runnable op) {
		Map<String, Long> before = checksums();
		Map<String, Map<String, Map<String, String>>> beforeRows = new HashMap<>();
		for (String t : before.keySet()) {
			beforeRows.put(t, rows(t));
		}
		op.run();
		Map<String, Long> after = checksums();
		Map<String, Map<String, Map<String, String>>> changes = new TreeMap<>();
		List<String[]> raw = new ArrayList<>(); // table, kind, text
		for (String t : after.keySet()) {
			if (after.get(t).equals(before.get(t))) {
				continue;
			}
			Map<String, Map<String, String>> b = beforeRows.getOrDefault(t, Map.of());
			Map<String, Map<String, String>> a = rows(t);
			for (var e : a.entrySet()) {
				Map<String, String> old = b.get(e.getKey());
				if (old == null) {
					raw.add(new String[] { t, "insert", e.getValue().toString() });
				} else if (!old.equals(e.getValue())) {
					Map<String, String> diff = new TreeMap<>();
					e.getValue().forEach((k, v) -> {
						if (!java.util.Objects.equals(v, old.get(k))) {
							diff.put(k, old.get(k) + " -> " + v);
						}
					});
					raw.add(new String[] { t, "update " + e.getKey(), diff.toString() });
				}
			}
			for (var e : b.entrySet()) {
				if (!a.containsKey(e.getKey())) {
					raw.add(new String[] { t, "delete", e.getValue().toString() });
				}
			}
		}
		// deterministic order (rows of a table come from a hash map), so placeholders are numbered the same way
		// numbers compare numerically (ids 99 and 100 must keep their order in both runs)
		raw.sort(java.util.Comparator.<String[], String>comparing(r -> r[0]).thenComparing(r -> r[1].startsWith("update") ? "update" : r[1])
				.thenComparing(r -> padNumbers(r[1].startsWith("update") ? r[1] + r[2] : r[2])));
		return normalize(raw);
	}

	/**
	 * New ids (values of inserted rows' id columns not seen before) become #n, ids of deleted / updated rows
	 * #dn; uuids become <uuid>. Placeholders are per column name and value (a foreign key column has the
	 * name of the referenced primary key in Zabbix), so equal numbers in unrelated tables do not mix.
	 */
	private static Map<String, List<String>> normalize(List<String[]> raw) {
		Map<String, String> idMap = new LinkedHashMap<>();
		Pattern idCol = ID_COL;
		int n = 0;
		for (String[] r : raw) {
			if (r[1].equals("insert")) {
				var m = idCol.matcher(r[2]);
				while (m.find()) {
					if (!idMap.containsKey(m.group(1) + "=" + m.group(2))) {
						idMap.put(m.group(1) + "=" + m.group(2), "#" + (++n));
					}
				}
			}
		}
		// ids of deleted / updated rows differ between runs too (each run works on its own object)
		int d = 0;
		for (String[] r : raw) {
			if (!r[1].equals("insert")) {
				var m = idCol.matcher(r[2]);
				while (m.find()) {
					if (!idMap.containsKey(m.group(1) + "=" + m.group(2))) {
						idMap.put(m.group(1) + "=" + m.group(2), "#d" + (++d));
					}
				}
				var key = UPDATE_KEY.matcher(r[1]);
				if (key.find() && !idMap.containsKey(r[0] + ":" + key.group(1))) {
					idMap.put(r[0] + ":" + key.group(1), "#d" + (++d));
				}
			}
		}
		Map<String, List<String>> out = new TreeMap<>();
		for (String[] r : raw) {
			String text = r[1] + " " + r[2];
			text = UUID32.matcher(text).replaceAll("<uuid>");
			// only id columns (and the key of updated rows): small values such as status=1 stay as they are
			var head = UPDATE_KEY.matcher(text);
			if (head.find()) {
				text = "update " + idMap.getOrDefault(r[0] + ":" + head.group(1), head.group(1)) + "|" + text.substring(head.end());
			}
			var m = ID_COL_CHANGE.matcher(text);
			StringBuilder sb = new StringBuilder();
			while (m.find()) {
				String col = m.group(1);
				String rep = col + "=" + idMap.getOrDefault(col + "=" + m.group(2), m.group(2))
						+ (m.group(3) == null ? "" : " -> " + idMap.getOrDefault(col + "=" + m.group(3), m.group(3)));
				m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
			}
			m.appendTail(sb);
			// function ids inside trigger expressions: {12345}
			var f = FUNCTION_REF.matcher(sb.toString());
			StringBuilder sf = new StringBuilder();
			while (f.find()) {
				f.appendReplacement(sf, java.util.regex.Matcher.quoteReplacement(
						"{" + idMap.getOrDefault("functionid=" + f.group(1), f.group(1)) + "}"));
			}
			f.appendTail(sf);
			out.computeIfAbsent(r[0], k -> new ArrayList<>()).add(sf.toString());
		}
		out.values().forEach(l -> l.sort(null));
		return out;
	}

	private static String padNumbers(String s) {
		var m = DIGITS.matcher(s);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			m.appendReplacement(sb, "0".repeat(Math.max(0, 20 - m.group().length())) + m.group());
		}
		m.appendTail(sb);
		return sb.toString();
	}

	static Set<String> tables(Map<String, List<String>> diff) {
		return new TreeSet<>(diff.keySet());
	}
}
