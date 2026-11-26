package org.tamacat.monitor.common;

import java.io.Serial;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Invalid input. {@link #fieldErrors()} maps a field name to a message key
 * (resolved by the UI with the user's locale).
 */
public class ValidationException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	private final transient Map<String, String> fieldErrors;

	public ValidationException(Map<String, String> fieldErrors) {
		super("validation failed: " + fieldErrors.keySet());
		this.fieldErrors = Map.copyOf(fieldErrors);
	}

	public static ValidationException of(String field, String messageKey) {
		return new ValidationException(Map.of(field, messageKey));
	}

	public Map<String, String> fieldErrors() {
		return fieldErrors;
	}

	/** Collects errors and throws once at the end. */
	public static final class Collector {
		private final Map<String, String> errors = new LinkedHashMap<>();

		public Collector add(String field, String messageKey) {
			errors.putIfAbsent(field, messageKey);
			return this;
		}

		public Collector check(boolean ok, String field, String messageKey) {
			return ok ? this : add(field, messageKey);
		}

		public void throwIfAny() {
			if (!errors.isEmpty()) {
				throw new ValidationException(errors);
			}
		}
	}
}
