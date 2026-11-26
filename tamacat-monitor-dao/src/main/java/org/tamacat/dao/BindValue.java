/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.dao;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import org.tamacat.dao.exception.InvalidParameterException;

/**
 * A typed value bound to a "?" placeholder.
 * <p>
 * Changed from tamacat-dao 2.0: values keep their Java type and are bound with the matching
 * setter (setLong, setTimestamp, ...) instead of setString. PostgreSQL rejects comparing a
 * bigint column with a varchar parameter, so typed binding is required for PostgreSQL support.
 * <p>
 * {@link #toString()} never prints the value, so bind values do not leak into logs.
 *
 * @param value   the value, or null for SQL NULL
 * @param sqlType the {@link Types} constant used for setNull
 */
public record BindValue(Object value, int sqlType) {

	public BindValue {
		if (value != null && !isSupported(value)) {
			throw new InvalidParameterException("Unsupported bind type: " + value.getClass().getName());
		}
	}

	private static boolean isSupported(Object v) {
		return v instanceof String || v instanceof Long || v instanceof Integer || v instanceof Short
				|| v instanceof BigDecimal || v instanceof Boolean || v instanceof Timestamp
				|| v instanceof Date || v instanceof byte[];
	}

	public static BindValue of(String value) {
		return new BindValue(Objects.requireNonNull(value, "value"), Types.VARCHAR);
	}

	public static BindValue of(long value) {
		return new BindValue(value, Types.BIGINT);
	}

	public static BindValue of(int value) {
		return new BindValue(value, Types.INTEGER);
	}

	public static BindValue of(BigDecimal value) {
		return new BindValue(Objects.requireNonNull(value, "value"), Types.NUMERIC);
	}

	public static BindValue of(boolean value) {
		return new BindValue(value, Types.BOOLEAN);
	}

	public static BindValue of(Instant value) {
		return new BindValue(Timestamp.from(Objects.requireNonNull(value, "value")), Types.TIMESTAMP);
	}

	public static BindValue of(LocalDate value) {
		return new BindValue(Date.valueOf(Objects.requireNonNull(value, "value")), Types.DATE);
	}

	public static BindValue of(byte[] value) {
		return new BindValue(Objects.requireNonNull(value, "value").clone(), Types.VARBINARY);
	}

	/** SQL NULL of the given {@link Types} type. */
	public static BindValue ofNull(int sqlType) {
		return new BindValue(null, sqlType);
	}

	/**
	 * Converts a plain Java value. Null is rejected because its SQL type is unknown;
	 * use {@link #ofNull(int)} instead.
	 */
	public static BindValue from(Object value) {
		return switch (value) {
			case null -> throw new InvalidParameterException("null needs an explicit type: use BindValue.ofNull(sqlType).");
			case BindValue b -> b;
			case String s -> of(s);
			case Long l -> of(l.longValue());
			case Integer i -> of(i.intValue());
			case Short s -> of(s.intValue());
			case BigDecimal d -> of(d);
			case Boolean b -> of(b.booleanValue());
			case Instant i -> of(i);
			case LocalDate d -> of(d);
			case byte[] b -> of(b);
			default -> throw new InvalidParameterException("Unsupported bind type: " + value.getClass().getName());
		};
	}

	public boolean isNull() {
		return value == null;
	}

	@Override
	public String toString() {
		return "BindValue[sqlType=" + sqlType + (value == null ? ", null" : "") + "]";
	}
}
