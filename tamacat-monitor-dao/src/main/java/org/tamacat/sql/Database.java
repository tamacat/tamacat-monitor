/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import javax.sql.DataSource;

import org.tamacat.dao.BindValue;
import org.tamacat.dao.PreparedSql;
import org.tamacat.dao.exception.DaoException;
import org.tamacat.sql.dialect.SqlDialect;

/**
 * A database: a {@link DataSource} with its {@link SqlDialect}.
 * <p>
 * Replaces the name-keyed static registries (DBAccessManager / ConnectionManager / db.xml) of
 * tamacat-dao 2.0. Instances are created explicitly, so databases can be added and removed
 * at runtime (e.g. when a Zabbix server is registered).
 *
 * <pre>
 * List&lt;Host&gt; hosts = db.queryList(sql, row -&gt; new Host(row.getLong("hostid"), row.getString("host")));
 * db.inTransaction(tx -&gt; { tx.update(a); tx.update(b); return null; });
 * </pre>
 */
public final class Database implements SqlOperations {

	private final String name;
	private final DataSource dataSource;
	private final SqlDialect dialect;
	private final JdbcExecutor executor;

	public Database(String name, DataSource dataSource, SqlDialect dialect) {
		this(name, dataSource, dialect, Duration.ofSeconds(30), SqlListener.logging(Duration.ofSeconds(1)));
	}

	/**
	 * @param queryTimeout statement timeout; zero for none
	 * @param listener     receives every executed statement (SQL text only, never values)
	 */
	public Database(String name, DataSource dataSource, SqlDialect dialect, Duration queryTimeout, SqlListener listener) {
		this.name = Objects.requireNonNull(name, "name");
		this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
		this.dialect = Objects.requireNonNull(dialect, "dialect");
		this.executor = new JdbcExecutor(name, (int) Math.min(Integer.MAX_VALUE, queryTimeout.toSeconds()),
				Objects.requireNonNull(listener, "listener"));
	}

	public String name() {
		return name;
	}

	public DataSource dataSource() {
		return dataSource;
	}

	@Override
	public SqlDialect dialect() {
		return dialect;
	}

	/** Runs {@code work} with one connection in auto-commit mode. */
	public <R> R withSession(Function<Session, R> work) {
		Connection c;
		try {
			c = dataSource.getConnection();
		} catch (SQLException e) {
			throw new DaoException("[" + name + "] Cannot obtain a connection", e);
		}
		Session session = new Session(c, executor, dialect);
		try {
			return work.apply(session);
		} finally {
			session.close();
			try {
				c.close();
			} catch (SQLException ignore) {
				// returned to the pool / closed; nothing more to do
			}
		}
	}

	public <R> R inTransaction(Function<Session, R> work) {
		return inTransaction(TxOptions.DEFAULT, work);
	}

	public <R> R inTransaction(TxOptions options, Function<Session, R> work) {
		return withSession(s -> s.inTransaction(options, work));
	}

	@Override
	public <T> List<T> queryList(PreparedSql sql, RowMapper<T> mapper) {
		return withSession(s -> s.queryList(sql, mapper));
	}

	@Override
	public <T> Optional<T> queryOne(PreparedSql sql, RowMapper<T> mapper) {
		return withSession(s -> s.queryOne(sql, mapper));
	}

	@Override
	public <R> R queryWith(PreparedSql sql, ResultSetHandler<R> handler) {
		return withSession(s -> s.queryWith(sql, handler));
	}

	@Override
	public int update(PreparedSql sql) {
		return withSession(s -> s.update(sql));
	}

	@Override
	public long insertAndGetKey(PreparedSql sql) {
		return withSession(s -> s.insertAndGetKey(sql));
	}

	@Override
	public int[] batch(String sql, List<List<BindValue>> rows) {
		return withSession(s -> s.batch(sql, rows));
	}

	@Override
	public String toString() {
		return "Database[" + name + ", " + dialect.name() + "]";
	}
}
