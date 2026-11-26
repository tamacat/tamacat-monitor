/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql;

import java.time.Duration;

/**
 * An executed statement.
 *
 * @param database     database name
 * @param sql          SQL text (no values)
 * @param bindCount    number of bound values
 * @param elapsedNanos execution time
 * @param error        the failure, or null on success
 */
public record SqlEvent(String database, String sql, int bindCount, long elapsedNanos, Throwable error) {

	public Duration elapsed() {
		return Duration.ofNanos(elapsedNanos);
	}

	public boolean failed() {
		return error != null;
	}
}
