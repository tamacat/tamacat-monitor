package org.tamacat.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tamacat.dao.BindValue;
import org.tamacat.dao.Conditions;
import org.tamacat.dao.PreparedSql;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.dao.exception.DaoException;
import org.tamacat.sql.dialect.SqlDialect;
import org.tamacat.sql.pool.ConnectionPool;
import org.tamacat.sql.pool.DriverConnectionFactory;
import org.tamacat.sql.pool.PoolConfig;

class DatabaseTest {

	record Host(long id, String name, Instant created) {
	}

	private ConnectionPool pool;
	private Database db;
	private final List<SqlEvent> events = new ArrayList<>();

	@BeforeEach
	void setUp() {
		String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
		pool = new ConnectionPool(PoolConfig.builder("test").maxSize(4).build(),
				new DriverConnectionFactory("org.h2.Driver", url, "sa", () -> "", null));
		db = new Database("test", pool, SqlDialect.MYSQL, Duration.ofSeconds(10), events::add);
		db.update(PreparedSql.of("create table hosts (hostid bigint auto_increment primary key, "
				+ "host varchar(128) not null, created timestamp, status int)"));
	}

	@AfterEach
	void tearDown() {
		pool.close();
	}

	private static Host map(Row r) throws java.sql.SQLException {
		return new Host(r.getLong("hostid"), r.getString("host"), r.getInstant("created"));
	}

	@Test
	void insertQueryWithTypedBinds() {
		Instant now = Instant.parse("2026-11-21T00:00:00Z");
		long id = db.insertAndGetKey(PreparedSql.of("insert into hosts (host, created, status) values (?, ?, ?)", "web_01", now, 0));
		db.update(PreparedSql.of("insert into hosts (host, created, status) values (?, ?, ?)",
				"web101", BindValue.ofNull(Types.TIMESTAMP), BindValue.ofNull(Types.INTEGER)));

		Host h = db.queryOne(PreparedSql.of("select * from hosts where hostid = ?", id), DatabaseTest::map).orElseThrow();
		assertEquals("web_01", h.name());
		assertEquals(now, h.created());

		// "_" must match literally: web101 must not match "web_"
		List<Host> found = db.queryList(SqlBuilder.of("select * from hosts")
				.where(Conditions.like("host", "web_", LikeMode.PREFIX)).build(), DatabaseTest::map);
		assertEquals(1, found.size());
		assertEquals("web_01", found.get(0).name());

		Integer status = db.queryOne(PreparedSql.of("select status from hosts where host = ?", "web101"),
				r -> r.getNullableInt("status")).orElse(null);
		assertEquals(null, status);
	}

	@Test
	void queryOneRejectsMultipleRows() {
		db.update(PreparedSql.of("insert into hosts (host) values (?), (?)", "a", "b"));
		assertThrows(DaoException.class, () -> db.queryOne(PreparedSql.of("select * from hosts"), DatabaseTest::map));
	}

	@Test
	void transactionCommitsOrRollsBack() {
		db.inTransaction(tx -> {
			tx.update(PreparedSql.of("insert into hosts (host) values (?)", "committed"));
			return null;
		});
		assertThrows(IllegalStateException.class, () -> db.inTransaction(tx -> {
			tx.update(PreparedSql.of("insert into hosts (host) values (?)", "rolled-back"));
			throw new IllegalStateException("boom");
		}));
		List<String> names = db.queryList(PreparedSql.of("select host from hosts order by host"), r -> r.getString("host"));
		assertEquals(List.of("committed"), names);
		// the connection went back to the pool in auto-commit mode
		assertEquals(0, pool.stats().active());
	}

	@Test
	void nestedTransactionIsRejected() {
		assertThrows(IllegalStateException.class,
				() -> db.inTransaction(tx -> tx.inTransaction(TxOptions.DEFAULT, inner -> null)));
	}

	@Test
	void sessionCannotEscapeItsCallback() {
		Session leaked = db.withSession(s -> s);
		assertThrows(IllegalStateException.class, () -> leaked.update(PreparedSql.of("delete from hosts")));
	}

	@Test
	void batchAndListenerWithoutValues() {
		int[] counts = db.batch("insert into hosts (host) values (?)",
				List.of(List.of(BindValue.of("secret-a")), List.of(BindValue.of("secret-b"))));
		assertEquals(2, counts.length);
		assertTrue(events.stream().noneMatch(e -> e.sql().contains("secret")));
		assertThrows(DaoException.class, () -> db.update(PreparedSql.of("insert into no_such_table values (?)", 1)));
		assertTrue(events.get(events.size() - 1).failed());
	}
}
