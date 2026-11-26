package org.tamacat.monitor.zabbix.query;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Database;
import org.tamacat.monitor.common.JsonText;
import org.tamacat.monitor.zabbix.reader.DashboardReader.Summary;

/**
 * Last successful full (unscoped) summary of each server, kept in {@code dashboard_cache} of the
 * central DB. Shown to server administrators, marked as stale, when the server cannot be reached.
 * Scoped summaries are never stored (they differ per user).
 */
final class LastKnownSummaries {

	record LastKnown(Instant collectedAt, Summary summary) {
	}

	private static final Pattern PAIR = Pattern.compile("\"(\\w+)\"\\s*:\\s*(-?\\d+)");

	private final Database central;

	LastKnownSummaries(Database central) {
		this.central = central;
	}

	void save(long serverId, Instant collectedAt, Summary summary) {
		String payload = JsonText.object(summary.toMap());
		central.inTransaction(tx -> {
			int n = tx.update(PreparedSql.of("update dashboard_cache set collected_at = ?, payload = ? where server_id = ?",
					collectedAt, payload, serverId));
			if (n == 0) {
				tx.update(PreparedSql.of("insert into dashboard_cache (server_id, collected_at, payload) values (?, ?, ?)",
						serverId, collectedAt, payload));
			}
			return null;
		});
	}

	Optional<LastKnown> find(long serverId) {
		return central.queryOne(PreparedSql.of("select collected_at, payload from dashboard_cache where server_id = ?", serverId),
				r -> new LastKnown(r.getInstant("collected_at"), Summary.fromMap(parse(r.getString("payload")))));
	}

	/** Reads the flat {"name":number} object written by {@link #save}. */
	static Map<String, Integer> parse(String json) {
		Map<String, Integer> map = new LinkedHashMap<>();
		if (json == null) {
			return map;
		}
		String s = json.startsWith("\"") ? json.substring(1, json.length() - 1).replace("\\\"", "\"") : json;
		Matcher m = PAIR.matcher(s);
		while (m.find()) {
			map.put(m.group(1), Integer.parseInt(m.group(2)));
		}
		return map;
	}
}
