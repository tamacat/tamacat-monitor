package org.tamacat.monitor.zabbix;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tamacat.sql.Database;
import org.tamacat.sql.SqlListener;
import org.tamacat.sql.dialect.SqlDialect;
import org.tamacat.sql.pool.ConnectionPool;
import org.tamacat.sql.pool.DriverConnectionFactory;
import org.tamacat.sql.pool.PoolConfig;
import org.tamacat.monitor.config.DatabaseFactory;

/**
 * Connection pools to the Zabbix DBs (read user), created on first use and closed when a server
 * is changed or removed. Pools are small: Zabbix DBs are shared with the Zabbix server itself.
 */
public final class ZabbixDatabases implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(ZabbixDatabases.class);

	/**
	 * Connection settings for one server. The password supplier decrypts on each new connection.
	 * {@code write} selects the pool of the write account (separate from the read pool).
	 */
	public record Target(long serverId, String name, String url, String user, Supplier<String> password, boolean write) {
		public Target(long serverId, String name, String url, String user, Supplier<String> password) {
			this(serverId, name, url, user, password, false);
		}

		String key() {
			return (write ? "w:" : "r:") + serverId;
		}
	}

	private record Entry(Database db, ConnectionPool pool) {
	}

	private final Map<String, Entry> pools = new ConcurrentHashMap<>();
	private final int maxSize;
	private final Duration queryTimeout;

	public ZabbixDatabases(int maxSize, Duration queryTimeout) {
		this.maxSize = maxSize;
		this.queryTimeout = queryTimeout;
	}

	public Database get(Target target) {
		return pools.computeIfAbsent(target.key(), k -> open(target)).db();
	}

	/** Opens a connection outside the pools and runs {@code work} (connection test of unsaved settings). */
	public static <R> R withSingleConnection(String url, String user, String password,
			java.util.function.Function<Database, R> work) {
		SqlDialect dialect = SqlDialect.fromJdbcUrl(url);
		PoolConfig pc = PoolConfig.builder("zabbix-test").minIdle(0).maxSize(1)
				.connectionTimeout(Duration.ofSeconds(15)).build();
		try (ConnectionPool pool = new ConnectionPool(pc, new DriverConnectionFactory(
				DatabaseFactory.defaultDriver(dialect), url, user, () -> password, DatabaseFactory.hardening(dialect)))) {
			return work.apply(new Database("zabbix-test", pool, dialect, Duration.ofSeconds(15), SqlListener.none()));
		}
	}

	private Entry open(Target t) {
		SqlDialect dialect = SqlDialect.fromJdbcUrl(t.url());
		PoolConfig pc = PoolConfig.builder("zabbix-" + t.key()).minIdle(0).maxSize(t.write() ? 2 : maxSize)
				.connectionTimeout(Duration.ofSeconds(15)).idleTimeout(Duration.ofMinutes(5)).build();
		ConnectionPool pool = new ConnectionPool(pc, new DriverConnectionFactory(
				DatabaseFactory.defaultDriver(dialect), t.url(), t.user(), t.password(), DatabaseFactory.hardening(dialect)));
		LOG.info("Zabbix DB {} pool opened for server {} ({})", t.write() ? "write" : "read", t.serverId(), t.name());
		return new Entry(new Database("zabbix-" + t.key(), pool, dialect, queryTimeout,
				SqlListener.logging(Duration.ofSeconds(2))), pool);
	}

	/** Closes the pool of a server (after its settings changed or it was removed). */
	public void invalidate(long serverId) {
		for (String key : new String[] { "r:" + serverId, "w:" + serverId }) {
			Entry e = pools.remove(key);
			if (e != null) {
				e.pool().close();
			}
		}
	}

	@Override
	public void close() {
		pools.values().forEach(e -> e.pool().close());
		pools.clear();
	}
}
