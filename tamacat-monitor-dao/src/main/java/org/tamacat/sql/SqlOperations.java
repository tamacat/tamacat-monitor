/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql;

import java.util.List;
import java.util.Optional;

import org.tamacat.dao.BindValue;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.dialect.SqlDialect;

/**
 * SQL execution. Implemented by {@link Database} (one pooled connection per call) and
 * {@link Session} (one connection for a unit of work or a transaction).
 * Failures are thrown as {@link org.tamacat.dao.exception.DaoException}.
 */
public interface SqlOperations {

	SqlDialect dialect();

	<T> List<T> queryList(PreparedSql sql, RowMapper<T> mapper);

	/** @throws org.tamacat.dao.exception.DaoException if more than one row is returned */
	<T> Optional<T> queryOne(PreparedSql sql, RowMapper<T> mapper);

	<R> R queryWith(PreparedSql sql, ResultSetHandler<R> handler);

	int update(PreparedSql sql);

	/** Executes an INSERT and returns the generated key (AUTO_INCREMENT / identity). */
	long insertAndGetKey(PreparedSql sql);

	/** Executes the same statement for each row of values. */
	int[] batch(String sql, List<List<BindValue>> rows);
}
