package org.tamacat.sql.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConnectionPoolTest {

	private String url;
	private final AtomicInteger created = new AtomicInteger();
	private ConnectionPool pool;

	@BeforeEach
	void setUp() throws SQLException {
		url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
	}

	@AfterEach
	void tearDown() {
		if (pool != null) {
			pool.close();
		}
	}

	private ConnectionPool newPool(PoolConfig.Builder b) {
		DriverConnectionFactory h2 = new DriverConnectionFactory("org.h2.Driver", url, "sa", () -> "", null);
		pool = new ConnectionPool(b.build(), () -> {
			created.incrementAndGet();
			return h2.create();
		});
		return pool;
	}

	private static Connection physical(Connection c) throws SQLException {
		return c.unwrap(Connection.class);
	}

	@Test
	void reusesReturnedConnection() throws SQLException {
		newPool(PoolConfig.builder("t").minIdle(0).maxSize(2));
		Connection p1;
		try (Connection c = pool.getConnection()) {
			p1 = physical(c);
		}
		try (Connection c = pool.getConnection()) {
			assertSame(p1, physical(c));
		}
		assertEquals(1, created.get());
		assertEquals(new PoolStats("t", 1, 1, 0, 0, 2), pool.stats());
	}

	@Test
	void fillsMinIdleOnStart() {
		newPool(PoolConfig.builder("t").minIdle(2).maxSize(4));
		assertEquals(2, pool.stats().idle());
		assertEquals(2, created.get());
	}

	@Test
	void timesOutWhenExhausted() throws SQLException {
		newPool(PoolConfig.builder("t").minIdle(0).maxSize(1).connectionTimeout(Duration.ofMillis(200)));
		try (Connection c = pool.getConnection()) {
			long start = System.nanoTime();
			assertThrows(SQLTransientConnectionException.class, pool::getConnection);
			assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 150);
		}
		try (Connection c = pool.getConnection()) {
			assertFalse(c.isClosed());
		}
	}

	@Test
	void closedProxyCannotBeUsed() throws SQLException {
		newPool(PoolConfig.builder("t").minIdle(0).maxSize(1));
		Connection c = pool.getConnection();
		c.close();
		c.close(); // idempotent
		assertTrue(c.isClosed());
		assertFalse(c.isValid(1));
		assertThrows(SQLException.class, c::createStatement);
		assertEquals(0, pool.stats().active());
	}

	@Test
	void uncommittedWorkIsRolledBackAndStateReset() throws SQLException {
		newPool(PoolConfig.builder("t").minIdle(0).maxSize(1));
		try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
			s.execute("create table t (id int)");
		}
		try (Connection c = pool.getConnection()) {
			c.setAutoCommit(false);
			c.setReadOnly(false);
			c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
			try (Statement s = c.createStatement()) {
				s.executeUpdate("insert into t values (1)");
			}
			// returned without commit
		}
		try (Connection c = pool.getConnection(); Statement s = c.createStatement();
				ResultSet rs = s.executeQuery("select count(*) from t")) {
			assertTrue(c.getAutoCommit());
			assertEquals(Connection.TRANSACTION_READ_COMMITTED, c.getTransactionIsolation());
			rs.next();
			assertEquals(0, rs.getInt(1));
		}
	}

	@Test
	void openStatementsAreClosedOnReturn() throws SQLException {
		newPool(PoolConfig.builder("t").minIdle(0).maxSize(1));
		Statement leaked;
		try (Connection c = pool.getConnection()) {
			leaked = c.createStatement();
		}
		assertTrue(leaked.isClosed());
	}

	@Test
	void unresettableStateClosesTheConnection() throws SQLException {
		newPool(PoolConfig.builder("t").minIdle(0).maxSize(1));
		Connection first;
		try (Connection c = pool.getConnection()) {
			first = physical(c);
			c.setClientInfo("ApplicationName", "x");
		}
		assertTrue(first.isClosed());
		try (Connection c = pool.getConnection()) {
			assertNotSame(first, physical(c));
		}
	}

	@Test
	void expiredConnectionsAreReplaced() throws Exception {
		newPool(PoolConfig.builder("t").minIdle(0).maxSize(1).maxLifetime(Duration.ofMillis(50)));
		Connection first;
		try (Connection c = pool.getConnection()) {
			first = physical(c);
		}
		Thread.sleep(80);
		try (Connection c = pool.getConnection()) {
			assertNotSame(first, physical(c));
		}
		assertTrue(first.isClosed());
	}

	@Test
	void brokenConnectionIsNotReused() throws Exception {
		newPool(PoolConfig.builder("t").minIdle(0).maxSize(1).validationInterval(Duration.ofMillis(1)));
		Connection first;
		try (Connection c = pool.getConnection()) {
			first = physical(c);
		}
		first.close(); // the server dropped the connection while idle
		Thread.sleep(10); // longer than validationInterval, so isValid() runs on the next borrow
		try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
			assertTrue(s.execute("select 1"));
		}
		assertEquals(2, created.get());
		assertEquals(1, pool.stats().total());
	}

	@Test
	void neverExceedsMaxSizeUnderVirtualThreads() throws Exception {
		newPool(PoolConfig.builder("t").minIdle(0).maxSize(5).connectionTimeout(Duration.ofSeconds(20)));
		AtomicInteger maxActive = new AtomicInteger();
		List<Future<?>> futures = new ArrayList<>();
		try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < 300; i++) {
				futures.add(ex.submit(() -> {
					try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
						maxActive.accumulateAndGet(pool.stats().active(), Math::max);
						s.execute("select 1");
						Thread.sleep(2);
					}
					return null;
				}));
			}
			for (Future<?> f : futures) {
				f.get();
			}
		}
		assertTrue(maxActive.get() <= 5, "max active " + maxActive.get());
		assertTrue(created.get() <= 5, "created " + created.get());
		assertEquals(0, pool.stats().active());
		assertEquals(0, pool.stats().waiting());
	}

	@Test
	void closingThePoolClosesConnections() throws SQLException {
		newPool(PoolConfig.builder("t").minIdle(1).maxSize(2));
		Connection lent = pool.getConnection();
		Connection lentPhysical = physical(lent);
		pool.close();
		assertThrows(SQLException.class, pool::getConnection);
		assertFalse(lentPhysical.isClosed());
		lent.close();
		assertTrue(lentPhysical.isClosed());
		assertEquals(0, pool.stats().total());
	}

	@Test
	void invalidConfigIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> PoolConfig.builder("t").maxSize(0).build());
		assertThrows(IllegalArgumentException.class, () -> PoolConfig.builder("t").minIdle(3).maxSize(2).build());
		assertThrows(IllegalArgumentException.class, () -> PoolConfig.builder("t").validationTimeout(Duration.ofMillis(10)).build());
	}
}
