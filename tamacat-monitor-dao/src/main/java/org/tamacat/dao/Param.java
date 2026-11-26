/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.dao;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A condition fragment (for a WHERE clause) with its bind values.
 * Build it with {@link Conditions}, combine with {@link #and(Param)} / {@link #or(Param)}.
 *
 * @param sql    fragment text with "?" placeholders
 * @param values bind values, in placeholder order
 */
public record Param(String sql, List<BindValue> values) {

	public Param {
		values = values == null ? List.of() : List.copyOf(values);
		PlaceholderScanner.check(sql, values.size());
	}

	public static Param of(String sql, Object... args) {
		return new Param(sql, PreparedSql.toBindValues(args));
	}

	/** {@code this and other}. A null {@code other} returns this. */
	public Param and(Param other) {
		return other == null ? this : join(this, " and ", other, false);
	}

	/** {@code (this or other)}. A null {@code other} returns this. */
	public Param or(Param other) {
		return other == null ? this : join(this, " or ", other, true);
	}

	/** {@code not (this)} */
	public Param not() {
		return new Param("not (" + sql + ")", values);
	}

	/** {@code (this)} */
	public Param group() {
		return new Param("(" + sql + ")", values);
	}

	/** Joins non-null conditions with "and". Empty when no condition is given. */
	public static Optional<Param> allOf(Param... params) {
		return params == null ? Optional.empty() : allOf(Arrays.asList(params));
	}

	public static Optional<Param> allOf(Collection<Param> params) {
		return params.stream().filter(Objects::nonNull).reduce(Param::and);
	}

	private static Param join(Param a, String op, Param b, boolean parenthesize) {
		List<BindValue> v = new ArrayList<>(a.values.size() + b.values.size());
		v.addAll(a.values);
		v.addAll(b.values);
		String text = a.sql + op + b.sql;
		return new Param(parenthesize ? "(" + text + ")" : text, v);
	}

	@Override
	public String toString() {
		return "Param[sql=" + sql + ", binds=" + values.size() + "]";
	}
}
