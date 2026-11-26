/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.dao;

import java.util.ArrayList;
import java.util.List;

/**
 * An executable SQL statement: SQL text with "?" placeholders and the matching bind values.
 * <p>
 * Changed from tamacat-dao 2.0: the literal form ({@code PreparedSql.ofLiteral}) is removed.
 * Every statement is checked so that the placeholder count equals the value count.
 *
 * @param sql    SQL text
 * @param values bind values, in placeholder order
 */
public record PreparedSql(String sql, List<BindValue> values) {

	public PreparedSql {
		values = values == null ? List.of() : List.copyOf(values);
		PlaceholderScanner.check(sql, values.size());
	}

	/**
	 * @param sql  SQL text with "?" placeholders
	 * @param args values; plain Java values are converted by {@link BindValue#from(Object)}
	 */
	public static PreparedSql of(String sql, Object... args) {
		return new PreparedSql(sql, toBindValues(args));
	}

	/** Returns a new statement with {@code fragment} appended after a space. */
	public PreparedSql append(String fragment, Object... args) {
		List<BindValue> combined = new ArrayList<>(values);
		combined.addAll(toBindValues(args));
		return new PreparedSql(sql + " " + fragment, combined);
	}

	static List<BindValue> toBindValues(Object... args) {
		if (args == null || args.length == 0) {
			return List.of();
		}
		List<BindValue> list = new ArrayList<>(args.length);
		for (Object a : args) {
			list.add(BindValue.from(a));
		}
		return list;
	}

	@Override
	public String toString() {
		return "PreparedSql[sql=" + sql + ", binds=" + values.size() + "]";
	}
}
