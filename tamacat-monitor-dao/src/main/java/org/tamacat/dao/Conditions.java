/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.dao;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.tamacat.dao.exception.InvalidParameterException;
import org.tamacat.sql.IdentifierRules;
import org.tamacat.sql.LikeEscape;
import org.tamacat.sql.LikeMode;

/**
 * Factory of {@link Param} conditions. Column names are validated by {@link IdentifierRules};
 * values are always bound.
 * <p>
 * Replaces Condition / Search.Conditions / BindSqlBuilder of tamacat-dao 2.0.
 */
public final class Conditions {

	/** Upper bound of values in one IN list. */
	public static final int MAX_IN_VALUES = 10_000;

	private Conditions() {
	}

	public static Param eq(String column, Object value) {
		return compare(column, "=", value);
	}

	public static Param ne(String column, Object value) {
		return compare(column, "<>", value);
	}

	public static Param lt(String column, Object value) {
		return compare(column, "<", value);
	}

	public static Param le(String column, Object value) {
		return compare(column, "<=", value);
	}

	public static Param gt(String column, Object value) {
		return compare(column, ">", value);
	}

	public static Param ge(String column, Object value) {
		return compare(column, ">=", value);
	}

	public static Param between(String column, Object from, Object to) {
		return Param.of(IdentifierRules.validate(column) + " between ? and ?", from, to);
	}

	public static Param isNull(String column) {
		return Param.of(IdentifierRules.validate(column) + " is null");
	}

	public static Param isNotNull(String column) {
		return Param.of(IdentifierRules.validate(column) + " is not null");
	}

	/** {@code column in (?, ...)}. An empty collection matches nothing. */
	public static Param in(String column, Collection<?> values) {
		return inList(column, values, false);
	}

	/** {@code column not in (?, ...)}. An empty collection matches everything. */
	public static Param notIn(String column, Collection<?> values) {
		return inList(column, values, true);
	}

	/** {@code column like ? escape '!'}; % and _ in the value are matched literally. */
	public static Param like(String column, String value, LikeMode mode) {
		return Param.of(IdentifierRules.validate(column) + " like ? escape '" + LikeEscape.ESCAPE + "'",
				LikeEscape.pattern(value, mode));
	}

	/**
	 * Case-insensitive LIKE: {@code lower(column) like ? escape '!'}.
	 * Zabbix uses a binary (case-sensitive) collation on MySQL, so the column is lowered
	 * explicitly; this works the same on MySQL and PostgreSQL.
	 */
	public static Param likeIgnoreCase(String column, String value, LikeMode mode) {
		String lowered = value == null ? null : value.toLowerCase(Locale.ROOT);
		return Param.of("lower(" + IdentifierRules.validate(column) + ") like ? escape '" + LikeEscape.ESCAPE + "'",
				LikeEscape.pattern(lowered, mode));
	}

	private static Param compare(String column, String op, Object value) {
		return Param.of(IdentifierRules.validate(column) + " " + op + " ?", value);
	}

	private static Param inList(String column, Collection<?> values, boolean negate) {
		String col = IdentifierRules.validate(column);
		if (values == null || values.isEmpty()) {
			return Param.of(negate ? "1=1" : "1=0");
		}
		if (values.size() > MAX_IN_VALUES) {
			throw new InvalidParameterException("Too many IN values: " + values.size());
		}
		List<BindValue> binds = new ArrayList<>(values.size());
		StringBuilder sb = new StringBuilder(col).append(negate ? " not in (" : " in (");
		for (Object v : values) {
			if (!binds.isEmpty()) {
				sb.append(',');
			}
			sb.append('?');
			binds.add(BindValue.from(v));
		}
		return new Param(sb.append(')').toString(), binds);
	}
}
