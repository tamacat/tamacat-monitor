package org.tamacat.monitor.web.api;

import java.io.Serial;

/** Malformed request (400). */
public class BadRequestException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	public BadRequestException(String message) {
		super(message);
	}
}
