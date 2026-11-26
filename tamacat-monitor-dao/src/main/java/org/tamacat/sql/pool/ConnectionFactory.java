/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql.pool;

import java.sql.Connection;
import java.sql.SQLException;

/** Opens physical connections for a {@link ConnectionPool}. */
@FunctionalInterface
public interface ConnectionFactory {
	Connection create() throws SQLException;
}
