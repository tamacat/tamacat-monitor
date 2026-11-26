/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql.dialect;

import org.tamacat.dao.PreparedSql;

/** PostgreSQL 13+. */
public final class PostgreSqlDialect implements SqlDialect {

	PostgreSqlDialect() {
	}

	@Override
	public String name() {
		return "PostgreSQL";
	}

	@Override
	public PreparedSql tryLock(String lockName) {
		return PreparedSql.of("select case when pg_try_advisory_lock(hashtext(?)) then 1 else 0 end", lockName);
	}

	@Override
	public PreparedSql releaseLock(String lockName) {
		return PreparedSql.of("select case when pg_advisory_unlock(hashtext(?)) then 1 else 0 end", lockName);
	}
}
