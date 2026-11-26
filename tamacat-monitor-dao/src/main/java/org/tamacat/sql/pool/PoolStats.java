/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql.pool;

/**
 * Snapshot of a pool.
 *
 * @param name    pool name
 * @param total   open physical connections
 * @param idle    connections waiting in the pool
 * @param active  connections lent out
 * @param waiting callers waiting for a connection
 * @param maxSize configured maximum
 */
public record PoolStats(String name, int total, int idle, int active, int waiting, int maxSize) {
}
