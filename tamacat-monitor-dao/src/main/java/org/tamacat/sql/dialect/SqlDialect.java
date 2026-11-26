/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql.dialect;

import org.tamacat.dao.Page;
import org.tamacat.dao.PreparedSql;
import org.tamacat.dao.exception.InvalidParameterException;

/**
 * Differences between the supported databases.
 * <p>
 * Changed from tamacat-dao 2.0: PostgreSQL added, Oracle removed. Paging values are bound
 * instead of concatenated.
 */
public sealed interface SqlDialect permits MySqlDialect, PostgreSqlDialect {

	SqlDialect MYSQL = new MySqlDialect();
	SqlDialect POSTGRESQL = new PostgreSqlDialect();

	String name();

	/** Appends {@code limit ? offset ?} (supported by both MySQL and PostgreSQL). */
	default PreparedSql paginate(PreparedSql sql, Page page) {
		return sql.append("limit ? offset ?", page.limit(), page.offset());
	}

	/**
	 * A query returning one integer column: 1 if the named session lock was acquired, 0 if
	 * another session holds it. Does not wait.
	 */
	PreparedSql tryLock(String lockName);

	/** Releases a lock acquired by {@link #tryLock(String)} on the same connection. */
	PreparedSql releaseLock(String lockName);

	/** Selects the dialect from a JDBC URL. */
	static SqlDialect fromJdbcUrl(String url) {
		if (url != null) {
			if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:")) {
				return MYSQL;
			}
			if (url.startsWith("jdbc:postgresql:")) {
				return POSTGRESQL;
			}
		}
		throw new InvalidParameterException("Unsupported JDBC URL (MySQL or PostgreSQL required)");
	}
}
