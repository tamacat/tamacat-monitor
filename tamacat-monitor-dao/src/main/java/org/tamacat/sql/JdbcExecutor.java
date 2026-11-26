/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import org.tamacat.dao.BindValue;
import org.tamacat.dao.PreparedSql;
import org.tamacat.dao.exception.DaoException;
import org.tamacat.dao.exception.InvalidParameterException;

/**
 * Executes statements on a given connection. Replaces DBAccessManager / PreparedStatementBinder
 * of tamacat-dao 2.0 without ThreadLocal state: the connection is always passed in.
 */
final class JdbcExecutor {

	private final String database;
	private final int queryTimeoutSeconds;
	private final SqlListener listener;

	JdbcExecutor(String database, int queryTimeoutSeconds, SqlListener listener) {
		this.database = database;
		this.queryTimeoutSeconds = queryTimeoutSeconds;
		this.listener = listener;
	}

	<R> R query(Connection c, PreparedSql sql, ResultSetHandler<R> handler) {
		long start = System.nanoTime();
		Throwable error = null;
		try (PreparedStatement ps = c.prepareStatement(sql.sql())) {
			prepare(ps, sql.values());
			try (ResultSet rs = ps.executeQuery()) {
				return handler.handle(rs);
			}
		} catch (SQLException e) {
			error = e;
			throw failure(sql.sql(), e);
		} catch (RuntimeException | Error e) {
			error = e;
			throw e;
		} finally {
			fire(sql.sql(), sql.values().size(), start, error);
		}
	}

	int update(Connection c, PreparedSql sql) {
		long start = System.nanoTime();
		Throwable error = null;
		try (PreparedStatement ps = c.prepareStatement(sql.sql())) {
			prepare(ps, sql.values());
			return ps.executeUpdate();
		} catch (SQLException e) {
			error = e;
			throw failure(sql.sql(), e);
		} catch (RuntimeException | Error e) {
			error = e;
			throw e;
		} finally {
			fire(sql.sql(), sql.values().size(), start, error);
		}
	}

	long insertAndGetKey(Connection c, PreparedSql sql) {
		long start = System.nanoTime();
		Throwable error = null;
		try (PreparedStatement ps = c.prepareStatement(sql.sql(), Statement.RETURN_GENERATED_KEYS)) {
			prepare(ps, sql.values());
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				if (keys.next()) {
					return keys.getLong(1);
				}
			}
			throw new DaoException("No generated key returned: " + sql.sql());
		} catch (SQLException e) {
			error = e;
			throw failure(sql.sql(), e);
		} catch (RuntimeException | Error e) {
			error = e;
			throw e;
		} finally {
			fire(sql.sql(), sql.values().size(), start, error);
		}
	}

	int[] batch(Connection c, String sql, List<List<BindValue>> rows) {
		long start = System.nanoTime();
		Throwable error = null;
		int binds = 0;
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			if (queryTimeoutSeconds > 0) {
				ps.setQueryTimeout(queryTimeoutSeconds);
			}
			for (List<BindValue> row : rows) {
				new PreparedSql(sql, row); // validates the placeholder count
				bind(ps, row);
				ps.addBatch();
				binds += row.size();
			}
			return ps.executeBatch();
		} catch (SQLException e) {
			error = e;
			throw failure(sql, e);
		} catch (RuntimeException | Error e) {
			error = e;
			throw e;
		} finally {
			fire(sql, binds, start, error);
		}
	}

	private void prepare(PreparedStatement ps, List<BindValue> values) throws SQLException {
		if (queryTimeoutSeconds > 0) {
			ps.setQueryTimeout(queryTimeoutSeconds);
		}
		bind(ps, values);
	}

	static void bind(PreparedStatement ps, List<BindValue> values) throws SQLException {
		for (int i = 0; i < values.size(); i++) {
			BindValue v = values.get(i);
			int pos = i + 1;
			switch (v.value()) {
				case null -> ps.setNull(pos, v.sqlType());
				case String s -> ps.setString(pos, s);
				case Long l -> ps.setLong(pos, l);
				case Integer n -> ps.setInt(pos, n);
				case Short n -> ps.setShort(pos, n);
				case BigDecimal d -> ps.setBigDecimal(pos, d);
				case Boolean b -> ps.setBoolean(pos, b);
				case Timestamp t -> ps.setTimestamp(pos, t);
				case Date d -> ps.setDate(pos, d);
				case byte[] b -> ps.setBytes(pos, b);
				default -> throw new InvalidParameterException("Unsupported bind type: " + v.value().getClass().getName());
			}
		}
	}

	private DaoException failure(String sql, SQLException e) {
		return new DaoException("[" + database + "] SQL failed (SQLState=" + e.getSQLState() + "): " + sql, e);
	}

	private void fire(String sql, int bindCount, long start, Throwable error) {
		try {
			listener.onExecuted(new SqlEvent(database, sql, bindCount, System.nanoTime() - start, error));
		} catch (RuntimeException ignore) {
			// a listener must not break SQL execution
		}
	}
}
