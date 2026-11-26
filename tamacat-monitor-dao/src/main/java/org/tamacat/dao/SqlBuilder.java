/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.dao;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.tamacat.sql.dialect.SqlDialect;

/**
 * Builds a {@link PreparedSql} from text fragments and conditions.
 * Each appended fragment must carry exactly as many values as it has placeholders,
 * so values can never be concatenated into the SQL text.
 *
 * <pre>
 * PreparedSql sql = SqlBuilder.of("select h.hostid, h.host from hosts h")
 *     .where(Conditions.eq("h.status", 0), Conditions.likeIgnoreCase("h.host", q, LikeMode.CONTAINS))
 *     .orderBy(Sort.asc("h.host"))
 *     .build(dialect, Page.first(50));
 * </pre>
 */
public final class SqlBuilder {

	private final StringBuilder sql = new StringBuilder();
	private final List<BindValue> values = new ArrayList<>();

	public static SqlBuilder of(String text, Object... args) {
		return new SqlBuilder().append(text, args);
	}

	/** Appends a fragment (a space is inserted when needed). */
	public SqlBuilder append(String text, Object... args) {
		List<BindValue> binds = PreparedSql.toBindValues(args);
		PlaceholderScanner.check(text, binds.size());
		appendText(text);
		values.addAll(binds);
		return this;
	}

	public SqlBuilder append(Param param) {
		appendText(param.sql());
		values.addAll(param.values());
		return this;
	}

	public SqlBuilder appendIf(boolean condition, String text, Object... args) {
		return condition ? append(text, args) : this;
	}

	/** Appends "where c1 and c2 ..." for the non-null conditions; nothing when all are null. */
	public SqlBuilder where(Param... conditions) {
		Param.allOf(conditions).ifPresent(p -> appendText("where").append(p));
		return this;
	}

	public SqlBuilder where(Collection<Param> conditions) {
		Param.allOf(conditions).ifPresent(p -> appendText("where").append(p));
		return this;
	}

	public SqlBuilder orderBy(Sort sort) {
		if (sort != null) {
			appendText(sort.toSql());
		}
		return this;
	}

	public PreparedSql build() {
		return new PreparedSql(sql.toString(), values);
	}

	public PreparedSql build(SqlDialect dialect, Page page) {
		PreparedSql base = build();
		return page == null ? base : dialect.paginate(base, page);
	}

	private SqlBuilder appendText(String text) {
		if (!sql.isEmpty() && !Character.isWhitespace(sql.charAt(sql.length() - 1))
				&& !text.isEmpty() && !Character.isWhitespace(text.charAt(0))) {
			sql.append(' ');
		}
		sql.append(text);
		return this;
	}
}
