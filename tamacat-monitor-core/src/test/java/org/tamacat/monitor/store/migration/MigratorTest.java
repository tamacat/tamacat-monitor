package org.tamacat.monitor.store.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Database;
import org.tamacat.sql.SqlListener;
import org.tamacat.sql.dialect.SqlDialect;
import org.tamacat.sql.pool.ConnectionPool;
import org.tamacat.sql.pool.DriverConnectionFactory;
import org.tamacat.sql.pool.PoolConfig;

public class MigratorTest {

	/** H2 has no GET_LOCK / RELEASE_LOCK; these stand in for the MySQL functions. */
	public static int getLock(String name, int timeout) {
		return 1;
	}

	public static int releaseLock(String name) {
		return 1;
	}

	private ConnectionPool pool;
	private Database db;

	@BeforeEach
	void setUp() {
		String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
		pool = new ConnectionPool(PoolConfig.builder("mig").maxSize(2).build(),
				new DriverConnectionFactory("org.h2.Driver", url, "sa", () -> "", null));
		db = new Database("mig", pool, SqlDialect.MYSQL, Duration.ofSeconds(10), SqlListener.none());
		db.update(PreparedSql.of("create alias get_lock for \"" + MigratorTest.class.getName() + ".getLock\""));
		db.update(PreparedSql.of("create alias release_lock for \"" + MigratorTest.class.getName() + ".releaseLock\""));
	}

	@AfterEach
	void tearDown() {
		pool.close();
	}

	private Migrator migrator() {
		return new Migrator(db, getClass().getClassLoader(), "db/testmigration", Duration.ofSeconds(5));
	}

	@Test
	void appliesPendingMigrationsInVersionOrderOnce() {
		assertEquals(List.of(1, 2), migrator().migrate());
		assertEquals(List.of(), migrator().migrate());
		assertEquals(1L, db.queryOne(PreparedSql.of("select count(*) c from a"), r -> r.getLong("c")).orElseThrow());
		assertEquals("x;y", db.queryOne(PreparedSql.of("select note from a"), r -> r.getString("note")).orElseThrow());
		assertEquals(2L, db.queryOne(PreparedSql.of("select count(*) c from schema_version"), r -> r.getLong("c")).orElseThrow());
	}

	@Test
	void changedScriptStopsMigration() {
		migrator().migrate();
		db.update(PreparedSql.of("update schema_version set checksum = ? where version = ?", "0".repeat(64), 1));
		assertThrows(IllegalStateException.class, () -> migrator().migrate());
	}

	@Test
	void productionScriptsParse() {
		List<Migration> list = new Migrator(db, getClass().getClassLoader()).load();
		assertEquals(1, list.get(0).version());
		// 9 tables + 1 insert of the roles
		assertEquals(10, list.get(0).statements().size());
	}

	@Test
	void splitterHandlesQuotesAndComments() {
		List<String> s = SqlScriptSplitter.split("""
				-- comment; here
				insert into t values ('a;b', "c;d");
				/* x; */ select 1;
				select 'it''s';
				""");
		assertEquals(List.of("insert into t values ('a;b', \"c;d\")", "select 1", "select 'it''s'"), s);
	}
}
