package org.tamacat.monitor.security.webauthn;

import java.util.Arrays;

/**
 * Authenticator data (WebAuthn Level 3, 6.1): RP ID hash, flags, signature counter and, at registration,
 * the attested credential data (credential id and COSE public key).
 */
record AuthenticatorData(byte[] rpIdHash, int flags, long signCount, byte[] credentialId, byte[] credentialPublicKey) {

	static final int UP = 0x01;
	static final int UV = 0x04;
	static final int BE = 0x08;
	static final int BS = 0x10;
	static final int AT = 0x40;
	static final int ED = 0x80;

	/** Longest credential id accepted (the spec allows up to 1023 bytes). */
	static final int MAX_CREDENTIAL_ID = 1023;

	static AuthenticatorData parse(byte[] d) {
		if (d.length < 37) {
			throw new WebAuthnException("authData: too short");
		}
		byte[] rpIdHash = Arrays.copyOfRange(d, 0, 32);
		int flags = d[32] & 0xff;
		long count = ((d[33] & 0xffL) << 24) | ((d[34] & 0xffL) << 16) | ((d[35] & 0xffL) << 8) | (d[36] & 0xffL);
		int pos = 37;
		byte[] credId = null;
		byte[] key = null;
		if ((flags & AT) != 0) {
			if (d.length < pos + 18) {
				throw new WebAuthnException("authData: attested credential data truncated");
			}
			pos += 16; // AAGUID: not used (no attestation verification)
			int len = ((d[pos] & 0xff) << 8) | (d[pos + 1] & 0xff);
			pos += 2;
			if (len == 0 || len > MAX_CREDENTIAL_ID || d.length < pos + len) {
				throw new WebAuthnException("authData: bad credential id length");
			}
			credId = Arrays.copyOfRange(d, pos, pos + len);
			pos += len;
			Cbor.Item item = Cbor.decodeAt(d, pos);
			key = Arrays.copyOfRange(d, pos, item.end());
			pos = item.end();
		}
		if ((flags & ED) != 0) {
			pos = Cbor.decodeAt(d, pos).end(); // extensions: not used
		}
		if (pos != d.length) {
			throw new WebAuthnException("authData: trailing bytes");
		}
		return new AuthenticatorData(rpIdHash, flags, count, credId, key);
	}

	boolean userPresent() {
		return (flags & UP) != 0;
	}

	boolean userVerified() {
		return (flags & UV) != 0;
	}

	boolean backupEligible() {
		return (flags & BE) != 0;
	}

	boolean backedUp() {
		return (flags & BS) != 0;
	}
}
