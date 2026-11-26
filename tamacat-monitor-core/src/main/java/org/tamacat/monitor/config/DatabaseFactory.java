package org.tamacat.monitor.config;

import java.time.Duration;
import java.util.Properties;
import java.util.function.Supplier;

import org.tamacat.sql.Database;
import org.tamacat.sql.SqlListener;
import org.tamacat.sql.dialect.SqlDialect;
import org.tamacat.sql.pool.ConnectionPool;
import org.tamacat.sql.pool.DriverConnectionFactory;
import org.tamacat.sql.pool.PoolConfig;

/**
 * Creates a pooled {@link Database} from configuration keys under a prefix.
 *
 * <pre>
 * {prefix}.url                         JDBC URL (required, no credentials in it)
 * {prefix}.user                        user name
 * {prefix}.password / .password.file   password or Docker secret file
 * {prefix}.driver                      driver class (default by URL)
 * {prefix}.pool.min-idle               default 1
 * {prefix}.pool.max-size               default 10
 * {prefix}.pool.connection-timeout     seconds, default 30
 * {prefix}.pool.leak-detection         seconds, default 0 (off)
 * {prefix}.query-timeout               seconds, default 30
 * {prefix}.slow-query                  seconds, default 1
 * </pre>
 */
public final class DatabaseFactory {

	private DatabaseFactory() {
	}

	/** A database and the pool behind it; closing it closes the pool. */
	public record PooledDatabase(Database database, ConnectionPool pool) implements AutoCloseable {
		@Override
		public void close() {
			pool.close();
		}
	}

	public static PooledDatabase create(AppConfig config, String prefix, String name) {
		String url = config.required(prefix + ".url");
		SqlDialect dialect = SqlDialect.fromJdbcUrl(url);
		String driver = config.get(prefix + ".driver", defaultDriver(dialect));
		String user = config.get(prefix + ".user").orElse(null);
		Supplier<String> password = () -> config.secret(prefix + ".password").orElse(null);

		PoolConfig poolConfig = PoolConfig.builder(name)
				.minIdle(config.getInt(prefix + ".pool.min-idle", 1))
				.maxSize(config.getInt(prefix + ".pool.max-size", 10))
				.connectionTimeout(config.getSeconds(prefix + ".pool.connection-timeout", Duration.ofSeconds(30)))
				.leakDetectionThreshold(config.getSeconds(prefix + ".pool.leak-detection", Duration.ZERO))
				.build();
		ConnectionPool pool = new ConnectionPool(poolConfig,
				new DriverConnectionFactory(driver, url, user, password, hardening(dialect)));
		Database db = new Database(name, pool, dialect,
				config.getSeconds(prefix + ".query-timeout", Duration.ofSeconds(30)),
				SqlListener.logging(config.getSeconds(prefix + ".slow-query", Duration.ofSeconds(1))));
		return new PooledDatabase(db, pool);
	}

	public static String defaultDriver(SqlDialect dialect) {
		return dialect == SqlDialect.POSTGRESQL ? "org.postgresql.Driver" : "com.mysql.cj.jdbc.Driver";
	}

	/** Driver properties that turn off risky features regardless of the URL. */
	public static Properties hardening(SqlDialect dialect) {
		Properties p = new Properties();
		if (dialect == SqlDialect.MYSQL) {
			p.setProperty("allowLoadLocalInfile", "false");
			p.setProperty("allowUrlInLocalInfile", "false");
			p.setProperty("allowMultiQueries", "false");
			p.setProperty("autoDeserialize", "false");
			p.setProperty("connectionTimeZone", "UTC");
			p.setProperty("characterEncoding", "UTF-8");
		}
		return p;
	}
}
