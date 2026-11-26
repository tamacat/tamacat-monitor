package org.tamacat.monitor.store.migration;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an SQL script into statements at ";" outside quotes and comments.
 * Comments are removed. Procedural blocks (DELIMITER, $$) are not supported.
 */
final class SqlScriptSplitter {

	private SqlScriptSplitter() {
	}

	static List<String> split(String script) {
		List<String> result = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int len = script.length();
		for (int i = 0; i < len; i++) {
			char c = script.charAt(i);
			if (c == '\'' || c == '"' || c == '`') {
				int end = closing(script, i, c);
				current.append(script, i, end + 1);
				i = end;
			} else if (c == '-' && i + 1 < len && script.charAt(i + 1) == '-') {
				int nl = script.indexOf('\n', i);
				i = nl < 0 ? len : nl - 1;
			} else if (c == '/' && i + 1 < len && script.charAt(i + 1) == '*') {
				int end = script.indexOf("*/", i + 2);
				if (end < 0) {
					throw new IllegalArgumentException("Unterminated block comment");
				}
				i = end + 1;
			} else if (c == ';') {
				add(result, current);
			} else {
				current.append(c);
			}
		}
		add(result, current);
		return result;
	}

	private static int closing(String s, int start, char quote) {
		for (int i = start + 1; i < s.length(); i++) {
			if (s.charAt(i) == quote) {
				if (i + 1 < s.length() && s.charAt(i + 1) == quote) {
					i++;
					continue;
				}
				return i;
			}
		}
		throw new IllegalArgumentException("Unterminated quote in SQL script");
	}

	private static void add(List<String> list, StringBuilder sb) {
		String stmt = sb.toString().strip();
		if (!stmt.isEmpty()) {
			list.add(stmt);
		}
		sb.setLength(0);
	}
}
