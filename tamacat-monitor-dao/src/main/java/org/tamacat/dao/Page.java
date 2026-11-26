/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.dao;

import org.tamacat.dao.exception.InvalidParameterException;

/**
 * Paging (offset / limit). The limit is capped so that one query cannot read an unbounded
 * number of rows from large Zabbix tables.
 */
public record Page(int offset, int limit) {

	public static final int MAX_LIMIT = 10_000;

	public Page {
		if (offset < 0) {
			throw new InvalidParameterException("offset must be >= 0");
		}
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new InvalidParameterException("limit must be 1.." + MAX_LIMIT);
		}
	}

	public static Page first(int limit) {
		return new Page(0, limit);
	}

	/** @param pageNumber 1-based page number */
	public static Page ofPageNumber(int pageNumber, int pageSize) {
		if (pageNumber < 1) {
			throw new InvalidParameterException("pageNumber must be >= 1");
		}
		return new Page(Math.multiplyExact(pageNumber - 1, pageSize), pageSize);
	}
}
