/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LoggingSqlListener implements SqlListener {

	private static final Logger LOG = LoggerFactory.getLogger("org.tamacat.sql.SQL");

	private final long slowNanos;

	LoggingSqlListener(Duration slowThreshold) {
		this.slowNanos = slowThreshold.toNanos();
	}

	@Override
	public void onExecuted(SqlEvent e) {
		long ms = e.elapsed().toMillis();
		if (e.failed()) {
			LOG.warn("[{}] failed after {} ms: {} ({})", e.database(), ms, e.sql(), e.error().toString());
		} else if (slowNanos > 0 && e.elapsedNanos() >= slowNanos) {
			LOG.warn("[{}] slow query {} ms (binds={}): {}", e.database(), ms, e.bindCount(), e.sql());
		} else if (LOG.isDebugEnabled()) {
			LOG.debug("[{}] {} ms (binds={}): {}", e.database(), ms, e.bindCount(), e.sql());
		}
	}
}
