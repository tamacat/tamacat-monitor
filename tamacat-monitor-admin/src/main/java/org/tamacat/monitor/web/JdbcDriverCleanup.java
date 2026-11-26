package org.tamacat.monitor.web;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prevents class loader leaks of JDBC drivers packaged in the WAR.
 * <p>
 * The pool creates drivers directly and does not need DriverManager, but MySQL Connector/J
 * still registers itself there when its class is loaded and starts an "abandoned connection
 * cleanup" thread. The pool closes its own connections, so the thread is disabled before the
 * driver is loaded, and drivers loaded by this web application are deregistered on stop.
 */
final class JdbcDriverCleanup {

	private static final Logger LOG = LoggerFactory.getLogger(JdbcDriverCleanup.class);
	private static final String MYSQL_DISABLE_CLEANUP = "com.mysql.cj.disableAbandonedConnectionCleanup";

	private JdbcDriverCleanup() {
	}

	/** Call before any JDBC driver class is loaded. */
	static void beforeStart() {
		if (System.getProperty(MYSQL_DISABLE_CLEANUP) == null) {
			System.setProperty(MYSQL_DISABLE_CLEANUP, "true");
		}
	}

	/** Deregisters drivers loaded by {@code loader} and stops the MySQL cleanup thread if it runs. */
	static void afterStop(ClassLoader loader) {
		for (Driver d : Collections.list(DriverManager.getDrivers())) {
			if (d.getClass().getClassLoader() == loader) {
				try {
					DriverManager.deregisterDriver(d);
				} catch (SQLException e) {
					LOG.warn("Cannot deregister JDBC driver {}: {}", d.getClass().getName(), e.toString());
				}
			}
		}
		try {
			Class<?> c = Class.forName("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread", false, loader);
			c.getMethod("checkedShutdown").invoke(null);
		} catch (ClassNotFoundException e) {
			// MySQL driver not present
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.debug("MySQL cleanup thread shutdown: {}", e.toString());
		}
	}
}
