package org.tamacat.monitor.zabbix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Database;
import org.tamacat.sql.SqlListener;
import org.tamacat.sql.dialect.SqlDialect;
import org.tamacat.sql.pool.ConnectionPool;
import org.tamacat.sql.pool.DriverConnectionFactory;
import org.tamacat.sql.pool.PoolConfig;

class ZabbixVersionTest {

	@Test
	void parsesDbVersionMandatory() {
		assertEquals(Optional.of(ZabbixVersion.V6_0), ZabbixVersion.fromDbVersion(6000000));
		assertEquals(Optional.of(ZabbixVersion.V7_0), ZabbixVersion.fromDbVersion(7000000));
		assertEquals(Optional.of(ZabbixVersion.V8_0), ZabbixVersion.fromDbVersion(8000000));
		assertTrue(ZabbixVersion.fromDbVersion(5000000).isEmpty());
		assertTrue(ZabbixVersion.fromDbVersion(6040000).isEmpty());
		assertEquals("6.4", ZabbixVersion.labelOf(6040000));
	}

	@Test
	void detectsFromDatabase() {
		String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
		try (ConnectionPool pool = new ConnectionPool(PoolConfig.builder("zbx").minIdle(0).maxSize(1).build(),
				new DriverConnectionFactory("org.h2.Driver", url, "sa", () -> "", null))) {
			Database db = new Database("zbx", pool, SqlDialect.MYSQL, Duration.ofSeconds(5), SqlListener.none());
			db.update(PreparedSql.of("create table dbversion (dbversionid bigint, mandatory int, optional int)"));
			db.update(PreparedSql.of("insert into dbversion values (1, ?, ?)", 7000000, 7000032));

			var detected = ZabbixVersionDetector.detect(db);
			assertTrue(detected.supported());
			assertEquals(ZabbixVersion.V7_0, detected.version().orElseThrow());
			assertEquals("7.0", detected.label());

			db.update(PreparedSql.of("update dbversion set mandatory = ?", 7020000));
			assertFalse(ZabbixVersionDetector.detect(db).supported());
		}
	}
}
