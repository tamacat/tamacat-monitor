package org.tamacat.monitor.zabbix.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Database;
import org.tamacat.sql.SqlListener;
import org.tamacat.sql.dialect.SqlDialect;
import org.tamacat.sql.pool.ConnectionPool;
import org.tamacat.sql.pool.DriverConnectionFactory;
import org.tamacat.sql.pool.PoolConfig;
import org.tamacat.monitor.store.migration.Migrator;
import org.tamacat.monitor.zabbix.ZabbixServerServiceTest;
import org.tamacat.monitor.zabbix.query.DashboardService.ServerCard;
import org.tamacat.monitor.zabbix.reader.DashboardReader.Summary;

class DashboardServiceTest {

	private static Summary summary(int base) {
		return new Summary(base, 1, 0, 2, new int[] { 0, 1, 2, 3, 4, 5 }, 7, 100 + base, 3);
	}

	@Test
	void totalsSkipStaleAndMissingFigures() {
		var cards = List.of(
				new ServerCard(1, "a", "7.0", "OK", summary(10), Instant.now(), false, true),
				new ServerCard(2, "b", "6.0", "OK", summary(20), Instant.now(), false, true),
				new ServerCard(3, "c", "7.0", "ERROR", summary(1000), Instant.now(), true, true),
				new ServerCard(4, "d", "7.0", "TIMEOUT", null, null, false, false));
		Summary t = DashboardService.totals(cards);
		assertEquals(30, t.hostsMonitored());
		assertEquals(30, t.problems());
		assertEquals(10, t.problemsBySeverity()[5]);
		assertEquals(230, t.itemsEnabled());
	}

	@Test
	void lastKnownIsStoredAndUpdated() {
		String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE";
		try (ConnectionPool pool = new ConnectionPool(PoolConfig.builder("c").maxSize(2).build(),
				new DriverConnectionFactory("org.h2.Driver", url, "sa", () -> "", null))) {
			Database db = new Database("c", pool, SqlDialect.MYSQL, Duration.ofSeconds(5), SqlListener.none());
			db.update(PreparedSql.of("create alias get_lock for \"" + ZabbixServerServiceTest.class.getName() + ".getLock\""));
			db.update(PreparedSql.of("create alias release_lock for \"" + ZabbixServerServiceTest.class.getName() + ".releaseLock\""));
			new Migrator(db, getClass().getClassLoader()).migrate();
			Instant now = Instant.parse("2026-11-21T00:00:00Z");
			db.update(PreparedSql.of("insert into zabbix_servers (server_id, name, db_url, db_read_user, db_read_password, "
					+ "read_only, enabled, created_at, updated_at) values (5, 'z', 'jdbc:mysql://h/z', 'r', ?, true, true, ?, ?)",
					new byte[] { 1 }, now, now));

			LastKnownSummaries lk = new LastKnownSummaries(db);
			assertTrue(lk.find(5).isEmpty());
			lk.save(5, now, summary(10));
			lk.save(5, now.plusSeconds(60), summary(11));
			var found = lk.find(5).orElseThrow();
			assertEquals(now.plusSeconds(60), found.collectedAt());
			assertEquals(summary(11).toMap(), found.summary().toMap());
		}
	}

	@Test
	void parsesQuotedAndPlainPayloads() {
		assertEquals(5, LastKnownSummaries.parse("{\"hostsMonitored\":5}").get("hostsMonitored"));
		assertEquals(5, LastKnownSummaries.parse("\"{\\\"hostsMonitored\\\":5}\"").get("hostsMonitored"));
		assertTrue(LastKnownSummaries.parse(null).isEmpty());
	}
}
