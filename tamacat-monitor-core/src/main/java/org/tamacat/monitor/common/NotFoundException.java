package org.tamacat.monitor.common;

import java.io.Serial;

/** The requested record does not exist (or is not visible to the caller). */
public class NotFoundException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	public NotFoundException(String message) {
		super(message);
	}
}
