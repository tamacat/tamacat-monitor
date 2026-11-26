/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql.pool;

import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A small JDBC connection pool with no dependency on Tomcat or other libraries.
 * Replaces ConnectionManager / StackObjectPool of tamacat-dao 2.0.
 *
 * <ul>
 * <li>At most {@code maxSize} connections; callers wait up to {@code connectionTimeout}
 * on a fair {@link Semaphore} (cheap for virtual threads).</li>
 * <li>Idle connections are reused LIFO, so the warmest connection is used first.</li>
 * <li>A connection idle longer than {@code validationInterval} is checked with
 * {@link Connection#isValid(int)} before it is lent out.</li>
 * <li>Connections older than {@code maxLifetime} are replaced; idle connections beyond
 * {@code minIdle} are closed after {@code idleTimeout}.</li>
 * <li>Returned connections are reset (see {@link PooledConnectionHandler}).</li>
 * <li>Optional leak detection logs where a connection held too long was borrowed.</li>
 * </ul>
 */
public final class ConnectionPool implements DataSource, AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(ConnectionPool.class);

	private final PoolConfig config;
	private final ConnectionFactory factory;
	private final LinkedBlockingDeque<PoolEntry> idle = new LinkedBlockingDeque<>();
	private final Set<PoolEntry> borrowed = ConcurrentHashMap.newKeySet();
	private final Semaphore permits;
	private final AtomicInteger total = new AtomicInteger();
	private final AtomicInteger waiting = new AtomicInteger();
	private final Thread housekeeper;
	private volatile boolean closed;
	private volatile int loginTimeout;

	public ConnectionPool(PoolConfig config, ConnectionFactory factory) {
		this.config = Objects.requireNonNull(config, "config");
		this.factory = Objects.requireNonNull(factory, "factory");
		this.permits = new Semaphore(config.maxSize(), true);
		fillMinIdle();
		this.housekeeper = Thread.ofVirtual().name("pool-housekeeper-" + config.name()).start(this::housekeep);
		LOG.info("[{}] connection pool started (minIdle={}, maxSize={})", config.name(), config.minIdle(), config.maxSize());
	}

	public String name() {
		return config.name();
	}

	public PoolConfig config() {
		return config;
	}

	public PoolStats stats() {
		int idleCount = idle.size();
		int activeCount = borrowed.size();
		return new PoolStats(config.name(), total.get(), idleCount, activeCount, waiting.get(), config.maxSize());
	}

	public boolean isClosed() {
		return closed;
	}

	@Override
	public Connection getConnection() throws SQLException {
		ensureOpen();
		long timeoutNanos = config.connectionTimeout().toNanos();
		long deadline = System.nanoTime() + timeoutNanos;
		boolean acquired;
		waiting.incrementAndGet();
		try {
			acquired = permits.tryAcquire(timeoutNanos, TimeUnit.NANOSECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SQLTransientConnectionException("[" + name() + "] interrupted while waiting for a connection", "08001", e);
		} finally {
			waiting.decrementAndGet();
		}
		if (!acquired) {
			throw timeout();
		}
		try {
			PoolEntry entry = obtain(deadline);
			entry.borrowedNanos = System.nanoTime();
			entry.leakReported = false;
			entry.borrowSite = config.leakDetectionThreshold().isZero() ? null : new Throwable("Connection borrowed here");
			borrowed.add(entry);
			return (Connection) Proxy.newProxyInstance(ConnectionPool.class.getClassLoader(),
					new Class<?>[] { Connection.class }, new PooledConnectionHandler(this, entry));
		} catch (SQLException | RuntimeException | Error e) {
			permits.release();
			throw e;
		}
	}

	/** Gets a usable idle connection, or creates one. The caller holds a permit. */
	private PoolEntry obtain(long deadline) throws SQLException {
		while (true) {
			ensureOpen();
			PoolEntry entry = idle.pollFirst();
			if (entry == null && reserveSlot()) {
				try {
					return newEntry();
				} catch (SQLException | RuntimeException e) {
					total.decrementAndGet();
					throw e;
				}
			}
			if (entry == null) {
				// All slots are taken but we hold a permit, so a connection is being returned.
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0) {
					throw timeout();
				}
				try {
					entry = idle.pollFirst(remaining, TimeUnit.NANOSECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new SQLTransientConnectionException("[" + name() + "] interrupted", "08001", e);
				}
				if (entry == null) {
					throw timeout();
				}
			}
			if (isUsable(entry)) {
				return entry;
			}
			destroy(entry, "validation failed or expired");
		}
	}

	private boolean reserveSlot() {
		while (true) {
			int n = total.get();
			if (n >= config.maxSize()) {
				return false;
			}
			if (total.compareAndSet(n, n + 1)) {
				return true;
			}
		}
	}

	private PoolEntry newEntry() throws SQLException {
		Connection c = factory.create();
		try {
			return new PoolEntry(c);
		} catch (SQLException | RuntimeException e) {
			try {
				c.close();
			} catch (SQLException suppressed) {
				e.addSuppressed(suppressed);
			}
			throw e;
		}
	}

	private boolean isUsable(PoolEntry e) {
		long now = System.nanoTime();
		if (e.broken || e.ageNanos(now) >= config.maxLifetime().toNanos()) {
			return false;
		}
		if (e.idleNanos(now) < config.validationInterval().toNanos()) {
			return true;
		}
		try {
			return e.connection.isValid((int) config.validationTimeout().toSeconds());
		} catch (SQLException | RuntimeException ex) {
			return false;
		}
	}

	/** Called once per lent-out connection by its proxy. */
	void release(PoolEntry entry, boolean reusable) {
		try {
			borrowed.remove(entry);
			entry.borrowSite = null;
			long now = System.nanoTime();
			if (closed || !reusable || entry.broken || entry.ageNanos(now) >= config.maxLifetime().toNanos()) {
				destroy(entry, closed ? "pool closed" : "not reusable");
			} else {
				entry.lastUsedNanos = now;
				idle.offerFirst(entry);
			}
		} finally {
			permits.release();
		}
	}

	private void destroy(PoolEntry entry, String reason) {
		if (!entry.markDestroyed()) {
			return;
		}
		total.decrementAndGet();
		try {
			entry.connection.close();
		} catch (SQLException | RuntimeException e) {
			LOG.debug("[{}] close failed: {}", name(), e.toString());
		}
		LOG.debug("[{}] connection closed ({})", name(), reason);
	}

	private void housekeep() {
		long interval = config.housekeepingInterval().toMillis();
		while (!closed) {
			try {
				Thread.sleep(interval);
			} catch (InterruptedException e) {
				return;
			}
			try {
				evictIdle();
				detectLeaks();
				fillMinIdle();
			} catch (RuntimeException e) {
				LOG.warn("[{}] housekeeping failed: {}", name(), e.toString());
			}
		}
	}

	private void evictIdle() {
		long now = System.nanoTime();
		long idleTimeout = config.idleTimeout().toNanos();
		long maxLifetime = config.maxLifetime().toNanos();
		for (PoolEntry e : new ArrayList<>(idle)) {
			boolean expired = e.ageNanos(now) >= maxLifetime;
			boolean surplusIdle = e.idleNanos(now) >= idleTimeout && total.get() > config.minIdle();
			if ((expired || surplusIdle) && idle.remove(e)) {
				destroy(e, expired ? "max lifetime" : "idle timeout");
			}
		}
	}

	private void detectLeaks() {
		if (config.leakDetectionThreshold().isZero()) {
			return;
		}
		long now = System.nanoTime();
		long threshold = config.leakDetectionThreshold().toNanos();
		for (PoolEntry e : borrowed) {
			Throwable site = e.borrowSite;
			if (!e.leakReported && site != null && now - e.borrowedNanos >= threshold) {
				e.leakReported = true;
				LOG.warn("[{}] possible connection leak: held for {} ms", name(),
						TimeUnit.NANOSECONDS.toMillis(now - e.borrowedNanos), site);
			}
		}
	}

	private void fillMinIdle() {
		while (!closed && idle.size() < config.minIdle() && reserveSlot()) {
			try {
				idle.offerLast(newEntry());
			} catch (SQLException | RuntimeException e) {
				total.decrementAndGet();
				LOG.warn("[{}] cannot open connection: {}", name(), e.toString());
				return;
			}
		}
	}

	/**
	 * Closes idle connections and stops the pool. Connections still lent out are closed when
	 * they are returned.
	 */
	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		if (housekeeper != null) {
			housekeeper.interrupt();
		}
		List<PoolEntry> drained = new ArrayList<>();
		idle.drainTo(drained);
		drained.forEach(e -> destroy(e, "pool closed"));
		LOG.info("[{}] connection pool closed ({} connection(s) still lent out)", name(), borrowed.size());
	}

	private void ensureOpen() throws SQLException {
		if (closed) {
			throw new SQLException("[" + name() + "] connection pool is closed", "08003");
		}
	}

	private SQLTransientConnectionException timeout() {
		PoolStats s = stats();
		return new SQLTransientConnectionException("[" + name() + "] no connection available within "
				+ config.connectionTimeout().toMillis() + " ms (total=" + s.total() + ", active=" + s.active()
				+ ", idle=" + s.idle() + ", waiting=" + s.waiting() + ")", "08001");
	}

	// ---- DataSource ----

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		throw new SQLFeatureNotSupportedException("Credentials are configured on the pool");
	}

	@Override
	public PrintWriter getLogWriter() {
		return null;
	}

	@Override
	public void setLogWriter(PrintWriter out) {
		// logging goes through SLF4J
	}

	@Override
	public void setLoginTimeout(int seconds) {
		loginTimeout = seconds;
	}

	@Override
	public int getLoginTimeout() {
		return loginTimeout;
	}

	@Override
	public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		if (iface.isInstance(this)) {
			return iface.cast(this);
		}
		throw new SQLException("Not a wrapper for " + iface.getName());
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) {
		return iface.isInstance(this);
	}
}
