/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql;

import java.sql.Connection;

/**
 * Transaction options.
 *
 * @param isolation isolation level, or null to keep the connection default
 * @param readOnly  true to mark the transaction read-only
 */
public record TxOptions(Isolation isolation, boolean readOnly) {

	public static final TxOptions DEFAULT = new TxOptions(null, false);
	public static final TxOptions READ_ONLY = new TxOptions(null, true);

	public enum Isolation {
		READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
		REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
		SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

		final int level;

		Isolation(int level) {
			this.level = level;
		}
	}

	public static TxOptions of(Isolation isolation) {
		return new TxOptions(isolation, false);
	}
}
