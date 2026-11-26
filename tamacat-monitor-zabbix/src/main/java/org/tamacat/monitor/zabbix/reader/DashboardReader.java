package org.tamacat.monitor.zabbix.reader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.tamacat.dao.Page;
import org.tamacat.dao.Param;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.dao.Sort;
import org.tamacat.sql.SqlOperations;
import org.tamacat.monitor.zabbix.access.AccessScope;

/**
 * Aggregates for the dashboards (T-301), limited to the user's scope. Each figure is one
 * aggregate query; the results are cached by the caller.
 */
public final class DashboardReader {

	/**
	 * @param problemsBySeverity open problems per severity 0..5
	 * @param unacknowledged     open problems not acknowledged
	 * @param hostsUnavailable   monitored hosts whose main interface is not available
	 * @param itemsUnsupported   enabled items of monitored hosts in "not supported" state
	 */
	public record Summary(int hostsMonitored, int hostsDisabled, int hostsInMaintenance, int hostsUnavailable,
			int[] problemsBySeverity, int unacknowledged, int itemsEnabled, int itemsUnsupported) {

		public Summary {
			problemsBySeverity = problemsBySeverity.clone();
			if (problemsBySeverity.length != 6) {
				throw new IllegalArgumentException("6 severities");
			}
		}

		public int problems() {
			int sum = 0;
			for (int n : problemsBySeverity) {
				sum += n;
			}
			return sum;
		}

		/** Flat representation for the last-known cache. */
		public Map<String, Integer> toMap() {
			var m = new java.util.LinkedHashMap<String, Integer>();
			m.put("hostsMonitored", hostsMonitored);
			m.put("hostsDisabled", hostsDisabled);
			m.put("hostsInMaintenance", hostsInMaintenance);
			m.put("hostsUnavailable", hostsUnavailable);
			for (int i = 0; i < 6; i++) {
				m.put("severity" + i, problemsBySeverity[i]);
			}
			m.put("unacknowledged", unacknowledged);
			m.put("itemsEnabled", itemsEnabled);
			m.put("itemsUnsupported", itemsUnsupported);
			return m;
		}

		public static Summary fromMap(Map<String, Integer> m) {
			int[] sev = new int[6];
			for (int i = 0; i < 6; i++) {
				sev[i] = m.getOrDefault("severity" + i, 0);
			}
			return new Summary(m.getOrDefault("hostsMonitored", 0), m.getOrDefault("hostsDisabled", 0),
					m.getOrDefault("hostsInMaintenance", 0), m.getOrDefault("hostsUnavailable", 0), sev,
					m.getOrDefault("unacknowledged", 0), m.getOrDefault("itemsEnabled", 0),
					m.getOrDefault("itemsUnsupported", 0));
		}
	}

	/** A monitored host whose main interface is not available. */
	public record UnavailableHost(long hostId, String name, int interfaceType, String error) {
	}

	private static final String HOSTS = "h.status in (0, 1) and h.flags in (0, 4)";

	public Summary summary(SqlOperations db, AccessScope scope) {
		Param hostScope = scope.hostCondition("h.hostid");

		int[] hosts = db.queryOne(SqlBuilder.of("select "
				+ "coalesce(sum(case when h.status = 0 then 1 else 0 end), 0) as monitored, "
				+ "coalesce(sum(case when h.status = 1 then 1 else 0 end), 0) as disabled, "
				+ "coalesce(sum(case when h.status = 0 and h.maintenance_status = 1 then 1 else 0 end), 0) as maintenance "
				+ "from hosts h").where(Param.of(HOSTS), hostScope).build(),
				r -> new int[] { r.getInt("monitored"), r.getInt("disabled"), r.getInt("maintenance") }).orElseThrow();

		int unavailable = db.queryOne(SqlBuilder.of("select count(*) from hosts h")
				.where(Param.of("h.status = 0 and h.flags in (0, 4)"), hostScope, Param.of(
						"exists (select 1 from interface ui where ui.hostid = h.hostid and ui.main = 1 and ui.available = 2)"))
				.build(), r -> r.getInt(1)).orElse(0);

		int[] severity = new int[6];
		int[] unack = { 0 };
		db.queryList(SqlBuilder.of("select p.severity, count(*) as total, "
				+ "coalesce(sum(case when p.acknowledged = 0 then 1 else 0 end), 0) as unack from problem p")
				.where(Param.of("p.source = 0 and p.object = 0 and p.r_eventid is null"), scope.triggerCondition("p.objectid"))
				.append("group by p.severity").build(),
				r -> new int[] { r.getInt("severity"), r.getInt("total"), r.getInt("unack") })
				.forEach(row -> {
					if (row[0] >= 0 && row[0] < 6) {
						severity[row[0]] += row[1];
					}
					unack[0] += row[2];
				});

		int[] items = db.queryOne(SqlBuilder.of("select count(*) as enabled, "
				+ "coalesce(sum(case when r.state = 1 then 1 else 0 end), 0) as unsupported "
				+ "from items i join hosts h on h.hostid = i.hostid left join item_rtdata r on r.itemid = i.itemid")
				.where(Param.of("h.status = 0 and h.flags in (0, 4) and i.flags in (0, 4) and i.status = 0"), hostScope)
				.build(), r -> new int[] { r.getInt("enabled"), r.getInt("unsupported") }).orElseThrow();

		return new Summary(hosts[0], hosts[1], hosts[2], unavailable, severity, unack[0], items[0], items[1]);
	}

	public List<UnavailableHost> unavailableHosts(SqlOperations db, AccessScope scope, Page page) {
		List<Param> where = new ArrayList<>();
		where.add(Param.of("h.status = 0 and h.flags in (0, 4) and ui.main = 1 and ui.available = 2"));
		where.add(scope.hostCondition("h.hostid"));
		var sql = SqlBuilder.of("select h.hostid, h.name, ui.type, ui.error from hosts h join interface ui on ui.hostid = h.hostid")
				.where(where).orderBy(Sort.asc("h.name")).build(db.dialect(), page);
		return db.queryList(sql, r -> new UnavailableHost(r.getLong("hostid"), r.getString("name"), r.getInt("type"),
				r.getString("error")));
	}
}
