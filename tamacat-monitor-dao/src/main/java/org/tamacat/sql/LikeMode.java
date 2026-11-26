/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql;

/** Where the search text must appear in a LIKE match. */
public enum LikeMode {
	/** whole value */
	EXACT,
	/** value% */
	PREFIX,
	/** %value% */
	CONTAINS,
	/** %value */
	SUFFIX
}
