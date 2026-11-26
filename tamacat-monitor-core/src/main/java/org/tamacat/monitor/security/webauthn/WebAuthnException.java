package org.tamacat.monitor.security.webauthn;

/** A WebAuthn response that cannot be accepted; the message says why (for the audit log, not the user). */
public final class WebAuthnException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public WebAuthnException(String reason) {
		super(reason);
	}

	public WebAuthnException(String reason, Throwable cause) {
		super(reason, cause);
	}
}
