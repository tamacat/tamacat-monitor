/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql.pool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/** A physical connection and its bookkeeping. */
final class PoolEntry {

	final Connection connection;
	final long createdNanos;
	final int defaultIsolation;
	final int defaultHoldability;
	final String defaultCatalog;
	final String defaultSchema;

	volatile long lastUsedNanos;
	volatile long borrowedNanos;
	volatile Throwable borrowSite;
	volatile boolean leakReported;
	volatile boolean broken;

	private final AtomicBoolean destroyed = new AtomicBoolean();

	PoolEntry(Connection connection) throws SQLException {
		this.connection = connection;
		this.createdNanos = System.nanoTime();
		this.lastUsedNanos = createdNanos;
		if (!connection.getAutoCommit()) {
			connection.setAutoCommit(true);
		}
		this.defaultIsolation = connection.getTransactionIsolation();
		this.defaultHoldability = connection.getHoldability();
		this.defaultCatalog = quietly(connection::getCatalog);
		this.defaultSchema = quietly(connection::getSchema);
	}

	private interface SqlSupplier {
		String get() throws SQLException;
	}

	private static String quietly(SqlSupplier s) {
		try {
			return s.get();
		} catch (SQLException | RuntimeException | AbstractMethodError e) {
			return null;
		}
	}

	long ageNanos(long now) {
		return now - createdNanos;
	}

	long idleNanos(long now) {
		return now - lastUsedNanos;
	}

	/** @return true only for the first call; guards against double close and double counting. */
	boolean markDestroyed() {
		return destroyed.compareAndSet(false, true);
	}
}
