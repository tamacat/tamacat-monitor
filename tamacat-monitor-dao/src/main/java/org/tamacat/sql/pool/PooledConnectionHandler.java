/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql.pool;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dynamic proxy behind each lent-out {@link java.sql.Connection}.
 * <ul>
 * <li>{@code close()} returns the physical connection to the pool (once).</li>
 * <li>Any call after close fails, so a stale reference cannot use a connection lent to someone else.</li>
 * <li>Statements left open are closed on return.</li>
 * <li>Changed session state (auto-commit, read-only, isolation, catalog, schema, holdability)
 * is reset on return; an open transaction is rolled back.</li>
 * <li>A connection-level SQL error (SQLState class 08) marks the connection broken; it is
 * then closed instead of being reused.</li>
 * </ul>
 */
final class PooledConnectionHandler implements InvocationHandler {

	private static final Logger LOG = LoggerFactory.getLogger(PooledConnectionHandler.class);
	private static final int PRUNE_THRESHOLD = 32;

	private final ConnectionPool pool;
	private final PoolEntry entry;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final ReentrantLock lock = new ReentrantLock();
	private final List<Statement> statements = new ArrayList<>();

	private boolean autoCommit = true;
	private boolean dirtyAutoCommit;
	private boolean dirtyReadOnly;
	private boolean dirtyIsolation;
	private boolean dirtyCatalog;
	private boolean dirtySchema;
	private boolean dirtyHoldability;
	/** state the pool cannot reset cheaply: the connection is closed instead of reused */
	private boolean unresettable;

	PooledConnectionHandler(ConnectionPool pool, PoolEntry entry) {
		this.pool = pool;
		this.entry = entry;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		String name = method.getName();
		switch (name) {
			case "close" -> {
				close();
				return null;
			}
			case "isClosed" -> {
				return closed.get();
			}
			case "equals" -> {
				return proxy == args[0];
			}
			case "hashCode" -> {
				return System.identityHashCode(proxy);
			}
			case "toString" -> {
				return "PooledConnection[" + pool.name() + (closed.get() ? ", closed]" : "]");
			}
			default -> {
			}
		}
		if (closed.get()) {
			if (name.equals("isValid")) {
				return false;
			}
			throw new SQLException("Connection is closed", "08003");
		}
		track(name, args);
		try {
			Object result = method.invoke(entry.connection, args);
			if (result instanceof Statement s) {
				register(s);
			}
			return result;
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof SQLException se && isFatal(se)) {
				entry.broken = true;
			}
			throw cause;
		}
	}

	private void track(String name, Object[] args) {
		switch (name) {
			case "setAutoCommit" -> {
				dirtyAutoCommit = true;
				autoCommit = (Boolean) args[0];
			}
			case "setReadOnly" -> dirtyReadOnly = true;
			case "setTransactionIsolation" -> dirtyIsolation = true;
			case "setCatalog" -> dirtyCatalog = true;
			case "setSchema" -> dirtySchema = true;
			case "setHoldability" -> dirtyHoldability = true;
			case "setNetworkTimeout", "setTypeMap", "setClientInfo", "setShardingKey",
					"setShardingKeyIfValid", "beginRequest", "endRequest" -> unresettable = true;
			case "abort" -> entry.broken = true;
			default -> {
			}
		}
	}

	static boolean isFatal(SQLException e) {
		String state = e.getSQLState();
		return e instanceof SQLNonTransientConnectionException
				|| (state != null && (state.startsWith("08") || state.equals("57P01")));
	}

	private void register(Statement s) {
		lock.lock();
		try {
			if (statements.size() >= PRUNE_THRESHOLD) {
				statements.removeIf(PooledConnectionHandler::isClosedQuietly);
			}
			statements.add(s);
		} finally {
			lock.unlock();
		}
	}

	private static boolean isClosedQuietly(Statement s) {
		try {
			return s.isClosed();
		} catch (SQLException e) {
			return true;
		}
	}

	private void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		closeStatements();
		boolean reusable = !entry.broken && !unresettable && reset();
		pool.release(entry, reusable);
	}

	private void closeStatements() {
		lock.lock();
		try {
			for (Statement s : statements) {
				try {
					s.close();
				} catch (SQLException ignore) {
					// best effort
				}
			}
			statements.clear();
		} finally {
			lock.unlock();
		}
	}

	private boolean reset() {
		var c = entry.connection;
		try {
			if (!autoCommit) {
				c.rollback();
				c.setAutoCommit(true);
			} else if (dirtyAutoCommit && !c.getAutoCommit()) {
				c.setAutoCommit(true);
			}
			if (dirtyReadOnly) {
				c.setReadOnly(false);
			}
			if (dirtyIsolation) {
				c.setTransactionIsolation(entry.defaultIsolation);
			}
			if (dirtyCatalog && entry.defaultCatalog != null) {
				c.setCatalog(entry.defaultCatalog);
			}
			if (dirtySchema && entry.defaultSchema != null) {
				c.setSchema(entry.defaultSchema);
			}
			if (dirtyHoldability) {
				c.setHoldability(entry.defaultHoldability);
			}
			c.clearWarnings();
			return true;
		} catch (SQLException e) {
			LOG.debug("[{}] connection reset failed; it will be closed: {}", pool.name(), e.toString());
			return false;
		}
	}
}
