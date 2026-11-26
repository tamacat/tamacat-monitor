/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql;

/**
 * Escaping of LIKE patterns.
 * <p>
 * Changed from tamacat-dao 2.0: one fixed escape character ('!'), which is itself escaped,
 * instead of choosing from candidates that may all appear in the value (the old fallback
 * left % and _ unescaped). Used with {@code like ? escape '!'}, which MySQL and
 * PostgreSQL both accept.
 */
public final class LikeEscape {

	public static final char ESCAPE = '!';

	private LikeEscape() {
	}

	/** Escapes !, % and _ so that they match literally. */
	public static String escape(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(value.length() + 8);
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == ESCAPE || c == '%' || c == '_') {
				sb.append(ESCAPE);
			}
			sb.append(c);
		}
		return sb.toString();
	}

	/** The bound pattern for {@code value} and {@code mode}. */
	public static String pattern(String value, LikeMode mode) {
		String e = escape(value);
		return switch (mode) {
			case EXACT -> e;
			case PREFIX -> e + "%";
			case CONTAINS -> "%" + e + "%";
			case SUFFIX -> "%" + e;
		};
	}
}
