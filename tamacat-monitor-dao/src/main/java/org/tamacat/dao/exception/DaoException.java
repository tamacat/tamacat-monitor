/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.dao.exception;

import java.io.Serial;
import java.sql.SQLException;

/**
 * Unchecked exception for data access failures.
 * The message may contain SQL text but never bind values.
 */
public class DaoException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	public DaoException(String message) {
		super(message);
	}

	public DaoException(String message, Throwable cause) {
		super(message, cause);
	}

	/** SQLState of the underlying {@link SQLException}, or null. */
	public String getSqlState() {
		for (Throwable t = getCause(); t != null; t = t.getCause()) {
			if (t instanceof SQLException e) {
				return e.getSQLState();
			}
		}
		return null;
	}
}
