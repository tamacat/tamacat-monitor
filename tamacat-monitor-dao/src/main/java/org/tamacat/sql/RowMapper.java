/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql;

import java.sql.SQLException;

/** Maps the current row to an object (typically a record). */
@FunctionalInterface
public interface RowMapper<T> {
	T map(Row row) throws SQLException;
}
