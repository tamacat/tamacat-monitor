/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql.dialect;

import org.tamacat.dao.PreparedSql;

/** MySQL 8.0+ (also MariaDB). */
public final class MySqlDialect implements SqlDialect {

	MySqlDialect() {
	}

	@Override
	public String name() {
		return "MySQL";
	}

	@Override
	public PreparedSql tryLock(String lockName) {
		return PreparedSql.of("select coalesce(get_lock(?, 0), 0)", lockName);
	}

	@Override
	public PreparedSql releaseLock(String lockName) {
		return PreparedSql.of("select coalesce(release_lock(?), 0)", lockName);
	}
}
