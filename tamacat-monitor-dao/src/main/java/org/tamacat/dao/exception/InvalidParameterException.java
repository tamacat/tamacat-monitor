/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.dao.exception;

import java.io.Serial;

/**
 * Thrown when an SQL fragment, identifier or bind value is rejected before execution.
 */
public class InvalidParameterException extends IllegalArgumentException {

	@Serial
	private static final long serialVersionUID = 1L;

	public InvalidParameterException(String message) {
		super(message);
	}
}
