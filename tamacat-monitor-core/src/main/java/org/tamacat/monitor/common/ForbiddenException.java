package org.tamacat.monitor.common;

import java.io.Serial;

/** The user is not allowed to do this (403). */
public class ForbiddenException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	public ForbiddenException() {
		super("forbidden");
	}
}
