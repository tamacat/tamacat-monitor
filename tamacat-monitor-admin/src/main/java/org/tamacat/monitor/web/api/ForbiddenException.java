package org.tamacat.monitor.web.api;

import java.io.Serial;

/** The user lacks the required role (403). */
public class ForbiddenException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	public ForbiddenException() {
		super("forbidden");
	}
}
