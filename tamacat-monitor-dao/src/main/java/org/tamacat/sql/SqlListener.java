/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql;

import java.time.Duration;

/**
 * Receives an event after each statement. Replaces DaoExecuteHandler of tamacat-dao 2.0.
 * Events carry SQL text and the bind count only, never bind values.
 */
@FunctionalInterface
public interface SqlListener {

	void onExecuted(SqlEvent event);

	static SqlListener none() {
		return e -> {
		};
	}

	/**
	 * Logs failures and statements slower than {@code slowThreshold} at WARN,
	 * everything else at DEBUG (logger "org.tamacat.sql.SQL").
	 */
	static SqlListener logging(Duration slowThreshold) {
		return new LoggingSqlListener(slowThreshold);
	}
}
