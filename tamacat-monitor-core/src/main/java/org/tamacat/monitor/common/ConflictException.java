package org.tamacat.monitor.common;

import java.io.Serial;

/** The change conflicts with existing data (duplicate name, last administrator, ...). */
public class ConflictException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	private final String messageKey;

	public ConflictException(String messageKey) {
		super(messageKey);
		this.messageKey = messageKey;
	}

	public String messageKey() {
		return messageKey;
	}
}
