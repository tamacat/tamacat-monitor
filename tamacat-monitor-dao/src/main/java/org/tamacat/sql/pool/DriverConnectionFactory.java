/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql.pool;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Opens connections through a JDBC {@link Driver} instance.
 * <p>
 * The driver is instantiated directly instead of going through {@code DriverManager}, so a
 * driver packaged in a WAR works without global registration and is not leaked on undeploy.
 * The password is obtained from the supplier for each new connection and is not kept,
 * so a rotated secret file is picked up without restart.
 */
public final class DriverConnectionFactory implements ConnectionFactory {

	private final Driver driver;
	private final String url;
	private final String user;
	private final Supplier<String> password;
	private final Properties properties;

	/**
	 * @param driverClassName JDBC driver class (e.g. com.mysql.cj.jdbc.Driver)
	 * @param url             JDBC URL; must not contain credentials
	 * @param user            user name, or null
	 * @param password        password supplier, or null
	 * @param properties      extra driver properties, or null
	 */
	public DriverConnectionFactory(String driverClassName, String url, String user,
			Supplier<String> password, Properties properties) {
		this.driver = loadDriver(Objects.requireNonNull(driverClassName, "driverClassName"));
		this.url = Objects.requireNonNull(url, "url");
		this.user = user;
		this.password = password;
		this.properties = new Properties();
		if (properties != null) {
			this.properties.putAll(properties);
		}
	}

	private static Driver loadDriver(String className) {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		if (loader == null) {
			loader = DriverConnectionFactory.class.getClassLoader();
		}
		try {
			Class<?> c = Class.forName(className, true, loader);
			return (Driver) c.getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException | ClassCastException e) {
			throw new IllegalArgumentException("Cannot load JDBC driver: " + className, e);
		}
	}

	@Override
	public Connection create() throws SQLException {
		Properties p = new Properties();
		p.putAll(properties);
		if (user != null) {
			p.setProperty("user", user);
		}
		String pw = password == null ? null : password.get();
		if (pw != null) {
			p.setProperty("password", pw);
		}
		Connection c = driver.connect(url, p);
		if (c == null) {
			throw new SQLException("JDBC driver " + driver.getClass().getName() + " does not accept the URL", "08001");
		}
		return c;
	}

	@Override
	public String toString() {
		return "DriverConnectionFactory[" + driver.getClass().getName() + ", user=" + user + "]";
	}
}
