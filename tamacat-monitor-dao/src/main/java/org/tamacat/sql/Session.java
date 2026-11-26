/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.tamacat.dao.BindValue;
import org.tamacat.dao.PreparedSql;
import org.tamacat.dao.exception.DaoException;
import org.tamacat.sql.dialect.SqlDialect;

/**
 * SQL operations on one connection. Obtained from {@link Database#withSession} or
 * {@link Database#inTransaction}; it is valid only inside that callback.
 * <p>
 * Replaces the ThreadLocal connection of DBAccessManager and the static Transaction of
 * tamacat-dao 2.0, so it works the same on platform threads and virtual threads.
 */
public final class Session implements SqlOperations {

	private final Connection connection;
	private final JdbcExecutor executor;
	private final SqlDialect dialect;
	private boolean open = true;
	private boolean inTransaction;

	Session(Connection connection, JdbcExecutor executor, SqlDialect dialect) {
		this.connection = connection;
		this.executor = executor;
		this.dialect = dialect;
	}

	@Override
	public SqlDialect dialect() {
		return dialect;
	}

	public boolean isInTransaction() {
		return inTransaction;
	}

	@Override
	public <T> List<T> queryList(PreparedSql sql, RowMapper<T> mapper) {
		ensureOpen();
		return executor.query(connection, sql, rs -> {
			Row row = new Row(rs);
			List<T> list = new ArrayList<>();
			while (rs.next()) {
				list.add(mapper.map(row));
			}
			return list;
		});
	}

	@Override
	public <T> Optional<T> queryOne(PreparedSql sql, RowMapper<T> mapper) {
		ensureOpen();
		return executor.query(connection, sql, rs -> {
			if (!rs.next()) {
				return Optional.<T>empty();
			}
			T value = mapper.map(new Row(rs));
			if (rs.next()) {
				throw new DaoException("More than one row returned: " + sql.sql());
			}
			return Optional.ofNullable(value);
		});
	}

	@Override
	public <R> R queryWith(PreparedSql sql, ResultSetHandler<R> handler) {
		ensureOpen();
		return executor.query(connection, sql, handler);
	}

	@Override
	public int update(PreparedSql sql) {
		ensureOpen();
		return executor.update(connection, sql);
	}

	@Override
	public long insertAndGetKey(PreparedSql sql) {
		ensureOpen();
		return executor.insertAndGetKey(connection, sql);
	}

	@Override
	public int[] batch(String sql, List<List<BindValue>> rows) {
		ensureOpen();
		return executor.batch(connection, sql, rows);
	}

	/**
	 * Runs {@code work} in a transaction: commit when it returns, rollback when it throws.
	 * Nested transactions are not supported.
	 */
	public <R> R inTransaction(TxOptions options, Function<Session, R> work) {
		ensureOpen();
		if (inTransaction) {
			throw new IllegalStateException("Nested transactions are not supported.");
		}
		boolean restoreReadOnly = false;
		int previousIsolation = -1;
		try {
			if (options.isolation() != null) {
				previousIsolation = connection.getTransactionIsolation();
				connection.setTransactionIsolation(options.isolation().level);
			}
			if (options.readOnly()) {
				connection.setReadOnly(true);
				restoreReadOnly = true;
			}
			connection.setAutoCommit(false);
		} catch (SQLException e) {
			throw new DaoException("Cannot start transaction", e);
		}
		inTransaction = true;
		Throwable primary = null;
		try {
			R result = work.apply(this);
			connection.commit();
			return result;
		} catch (SQLException e) {
			rollbackQuietly(e);
			primary = e;
			throw new DaoException("Commit failed", e);
		} catch (RuntimeException | Error e) {
			rollbackQuietly(e);
			primary = e;
			throw e;
		} finally {
			inTransaction = false;
			SQLException restoreError = restore(restoreReadOnly, previousIsolation);
			if (restoreError != null) {
				if (primary != null) {
					primary.addSuppressed(restoreError);
				} else {
					throw new DaoException("Cannot restore connection state after transaction", restoreError);
				}
			}
		}
	}

	private void rollbackQuietly(Throwable cause) {
		try {
			connection.rollback();
		} catch (SQLException e) {
			cause.addSuppressed(e);
		}
	}

	private SQLException restore(boolean readOnly, int isolation) {
		try {
			connection.setAutoCommit(true);
			if (readOnly) {
				connection.setReadOnly(false);
			}
			if (isolation >= 0) {
				connection.setTransactionIsolation(isolation);
			}
			return null;
		} catch (SQLException e) {
			return e;
		}
	}

	void close() {
		open = false;
	}

	private void ensureOpen() {
		if (!open) {
			throw new IllegalStateException("Session is closed; use it only inside its callback.");
		}
	}
}
