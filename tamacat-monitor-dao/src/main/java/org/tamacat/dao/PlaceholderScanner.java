/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.dao;

import org.tamacat.dao.exception.InvalidParameterException;

/**
 * Counts "?" placeholders outside quotes and comments.
 * <p>
 * Changed from tamacat-dao 2.0: also skips double-quoted and back-quoted identifiers,
 * "--" line comments and block comments.
 */
final class PlaceholderScanner {

	private PlaceholderScanner() {
	}

	/** @return the number of placeholders, or -1 if a quote or block comment is not terminated. */
	static int countPlaceholders(String sql) {
		int count = 0;
		int len = sql.length();
		for (int i = 0; i < len; i++) {
			char c = sql.charAt(i);
			switch (c) {
				case '\'', '"', '`' -> {
					int end = skipQuoted(sql, i, c);
					if (end < 0) {
						return -1;
					}
					i = end;
				}
				case '-' -> {
					if (i + 1 < len && sql.charAt(i + 1) == '-') {
						int nl = sql.indexOf('\n', i);
						i = nl < 0 ? len : nl;
					}
				}
				case '/' -> {
					if (i + 1 < len && sql.charAt(i + 1) == '*') {
						int end = sql.indexOf("*/", i + 2);
						if (end < 0) {
							return -1;
						}
						i = end + 1;
					}
				}
				case '?' -> count++;
				default -> {
				}
			}
		}
		return count;
	}

	/** @return index of the closing quote, or -1. A doubled quote is an escaped quote. */
	private static int skipQuoted(String sql, int start, char quote) {
		for (int i = start + 1; i < sql.length(); i++) {
			if (sql.charAt(i) == quote) {
				if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
					i++;
					continue;
				}
				return i;
			}
		}
		return -1;
	}

	static void check(String sql, int valueCount) {
		if (sql == null) {
			throw new InvalidParameterException("sql is required.");
		}
		int count = countPlaceholders(sql);
		if (count < 0) {
			throw new InvalidParameterException("Cannot verify placeholders: unterminated quote or comment. sql=[" + sql + "]");
		}
		if (count != valueCount) {
			throw new InvalidParameterException("Placeholder count mismatch: " + count
					+ " placeholder(s) but " + valueCount + " value(s). sql=[" + sql + "]");
		}
	}
}
