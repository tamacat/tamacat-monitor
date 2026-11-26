/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Typed, null-aware access to the current row of a {@link ResultSet}.
 */
public final class Row {

	private final ResultSet rs;

	Row(ResultSet rs) {
		this.rs = rs;
	}

	public String getString(String column) throws SQLException {
		return rs.getString(column);
	}

	/** 0 for SQL NULL (JDBC semantics). */
	public long getLong(String column) throws SQLException {
		return rs.getLong(column);
	}

	public Long getNullableLong(String column) throws SQLException {
		long v = rs.getLong(column);
		return rs.wasNull() ? null : v;
	}

	/** 0 for SQL NULL (JDBC semantics). */
	public int getInt(String column) throws SQLException {
		return rs.getInt(column);
	}

	public Integer getNullableInt(String column) throws SQLException {
		int v = rs.getInt(column);
		return rs.wasNull() ? null : v;
	}

	/** By 1-based column index (for unnamed expressions such as {@code count(*)}). */
	public String getString(int index) throws SQLException {
		return rs.getString(index);
	}

	public long getLong(int index) throws SQLException {
		return rs.getLong(index);
	}

	public int getInt(int index) throws SQLException {
		return rs.getInt(index);
	}

	public Long getNullableLong(int index) throws SQLException {
		long v = rs.getLong(index);
		return rs.wasNull() ? null : v;
	}

	public byte[] getBytes(int index) throws SQLException {
		return rs.getBytes(index);
	}

	public boolean getBoolean(String column) throws SQLException {
		return rs.getBoolean(column);
	}

	public BigDecimal getBigDecimal(String column) throws SQLException {
		return rs.getBigDecimal(column);
	}

	public Instant getInstant(String column) throws SQLException {
		Timestamp ts = rs.getTimestamp(column);
		return ts == null ? null : ts.toInstant();
	}

	/** For Zabbix "clock" columns: integer seconds since the epoch. */
	public Instant getEpochSecond(String column) throws SQLException {
		long v = rs.getLong(column);
		return rs.wasNull() ? null : Instant.ofEpochSecond(v);
	}

	public byte[] getBytes(String column) throws SQLException {
		return rs.getBytes(column);
	}

	/** The underlying result set, for column types not covered here. Do not advance it. */
	public ResultSet resultSet() {
		return rs;
	}
}
