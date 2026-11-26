/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.tamacat.sql.IdentifierRules;

/**
 * ORDER BY clause. Columns are validated by {@link IdentifierRules}. Sort keys that come
 * from a request must be mapped through an allow list with {@link #fromRequest}.
 */
public final class Sort {

	public enum Order {
		ASC, DESC;

		/** "desc" (any case) is DESC; anything else is ASC. */
		public static Order parse(String s) {
			return s != null && s.trim().equalsIgnoreCase("desc") ? DESC : ASC;
		}
	}

	private final List<String> items;

	private Sort(List<String> items) {
		this.items = List.copyOf(items);
	}

	public static Sort by(String column, Order order) {
		return new Sort(List.of(item(column, order)));
	}

	public static Sort asc(String column) {
		return by(column, Order.ASC);
	}

	public static Sort desc(String column) {
		return by(column, Order.DESC);
	}

	public Sort then(String column, Order order) {
		List<String> list = new ArrayList<>(items);
		list.add(item(column, order));
		return new Sort(list);
	}

	/**
	 * @param key     sort key sent by the client (e.g. "name")
	 * @param dir     "asc" or "desc"
	 * @param allowed map of allowed client keys to column expressions
	 * @return empty if the key is not allowed
	 */
	public static Optional<Sort> fromRequest(String key, String dir, Map<String, String> allowed) {
		if (key == null) {
			return Optional.empty();
		}
		String column = allowed.get(key.trim().toLowerCase(Locale.ROOT));
		return column == null ? Optional.empty() : Optional.of(by(column, Order.parse(dir)));
	}

	public String toSql() {
		return "order by " + String.join(", ", items);
	}

	private static String item(String column, Order order) {
		return IdentifierRules.validate(column) + " " + order.name().toLowerCase(Locale.ROOT);
	}

	@Override
	public String toString() {
		return toSql();
	}
}
