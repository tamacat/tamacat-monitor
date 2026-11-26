/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads a whole {@link ResultSet}. The result set is closed by the caller after
 * {@link #handle(ResultSet)} returns, so it must not escape.
 */
@FunctionalInterface
public interface ResultSetHandler<R> {
	R handle(ResultSet rs) throws SQLException;
}
