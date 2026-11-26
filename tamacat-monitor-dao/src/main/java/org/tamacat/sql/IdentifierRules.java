/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql;

import java.util.regex.Pattern;

import org.tamacat.dao.exception.InvalidParameterException;

/**
 * Validation of identifiers (table / column names) that cannot be bound with "?".
 * <p>
 * Changed from tamacat-dao 2.0: an allow list ({@code name} or {@code alias.name}, ASCII letters,
 * digits and underscore) instead of rejecting a few dangerous characters.
 */
public final class IdentifierRules {

	private static final Pattern IDENTIFIER =
			Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}(\\.[A-Za-z_][A-Za-z0-9_]{0,63})?");

	private IdentifierRules() {
	}

	public static boolean isValid(String raw) {
		return raw != null && IDENTIFIER.matcher(raw).matches();
	}

	/** @return {@code raw} when valid */
	public static String validate(String raw) {
		if (!isValid(raw)) {
			String shown = raw == null ? "null" : raw.length() > 64 ? raw.substring(0, 64) + "..." : raw;
			throw new InvalidParameterException("Unsafe identifier: [" + shown + "]");
		}
		return raw;
	}
}
