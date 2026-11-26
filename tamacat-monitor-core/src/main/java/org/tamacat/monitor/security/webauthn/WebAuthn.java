package org.tamacat.monitor.security.webauthn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Relying-party checks of WebAuthn (passkeys), Level 3 sections 7.1 (registration) and 7.2 (authentication).
 * <ul>
 * <li>Attestation is not verified (the "none" policy usual for passkeys): any format is accepted and its
 * statement ignored; the authenticator model is not trusted for anything.</li>
 * <li>Algorithms: ES256, EdDSA, RS256 ({@link CoseKey}).</li>
 * <li>The origin must be one of the configured origins and not cross-origin; the RP ID hash must match.</li>
 * <li>A signature counter that does not increase (when either value is non-zero) is refused as a possibly
 * cloned authenticator. Synced passkeys always report 0.</li>
 * </ul>
 */
public final class WebAuthn {

	/** The relying party: RP ID (a domain), display name and the accepted origins (scheme://host[:port]). */
	public record RelyingParty(String id, String name, Set<String> origins) {
		public RelyingParty {
			if (id == null || id.isBlank() || origins == null || origins.isEmpty()) {
				throw new IllegalArgumentException("RP ID and origins are required");
			}
			origins = Set.copyOf(origins);
		}
	}

	/** A verified new credential. {@code publicKey} is the COSE key to store. */
	public record NewCredential(byte[] credentialId, byte[] publicKey, int algorithm, long signCount, boolean userVerified,
			boolean backupEligible, boolean backedUp) {
	}

	/** A verified assertion. */
	public record Assertion(long signCount, boolean userVerified, boolean backedUp) {
	}

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

	private final RelyingParty rp;
	private final byte[] rpIdHash;

	public WebAuthn(RelyingParty rp) {
		this.rp = rp;
		this.rpIdHash = sha256(rp.id().getBytes(StandardCharsets.UTF_8));
	}

	public RelyingParty relyingParty() {
		return rp;
	}

	/** 32 random bytes. */
	public static byte[] newChallenge() {
		byte[] b = new byte[32];
		RANDOM.nextBytes(b);
		return b;
	}

	public static String base64Url(byte[] b) {
		return B64URL.encodeToString(b);
	}

	/** Decodes base64url (with or without padding); an invalid value is a {@link WebAuthnException}. */
	public static byte[] fromBase64Url(String s) {
		if (s == null) {
			throw new WebAuthnException("missing value");
		}
		try {
			return Base64.getUrlDecoder().decode(s.strip());
		} catch (IllegalArgumentException e) {
			throw new WebAuthnException("not base64url");
		}
	}

	/** Registration ceremony (navigator.credentials.create). */
	public NewCredential verifyRegistration(byte[] clientDataJson, byte[] attestationObject, byte[] expectedChallenge,
			boolean requireUserVerification) {
		checkClientData(clientDataJson, "webauthn.create", expectedChallenge);
		if (!(Cbor.decode(attestationObject) instanceof Map<?, ?> att) || !(att.get("authData") instanceof byte[] authData)
				|| !(att.get("fmt") instanceof String)) {
			throw new WebAuthnException("attestationObject: fmt or authData missing");
		}
		AuthenticatorData ad = AuthenticatorData.parse(authData);
		checkAuthenticatorData(ad, requireUserVerification);
		if (ad.credentialId() == null) {
			throw new WebAuthnException("authData: no attested credential data");
		}
		CoseKey key = CoseKey.parse(ad.credentialPublicKey()); // supported algorithm and a valid key
		return new NewCredential(ad.credentialId(), ad.credentialPublicKey(), key.algorithm(), ad.signCount(), ad.userVerified(),
				ad.backupEligible(), ad.backedUp());
	}

	/** Authentication ceremony (navigator.credentials.get) with the stored key and counter of the credential. */
	public Assertion verifyAssertion(byte[] clientDataJson, byte[] authenticatorData, byte[] signature, byte[] expectedChallenge,
			byte[] storedCoseKey, long storedSignCount, boolean requireUserVerification) {
		checkClientData(clientDataJson, "webauthn.get", expectedChallenge);
		AuthenticatorData ad = AuthenticatorData.parse(authenticatorData);
		checkAuthenticatorData(ad, requireUserVerification);
		byte[] hash = sha256(clientDataJson);
		byte[] signed = new byte[authenticatorData.length + hash.length];
		System.arraycopy(authenticatorData, 0, signed, 0, authenticatorData.length);
		System.arraycopy(hash, 0, signed, authenticatorData.length, hash.length);
		if (!CoseKey.parse(storedCoseKey).verify(signed, signature)) {
			throw new WebAuthnException("bad signature");
		}
		if ((ad.signCount() != 0 || storedSignCount != 0) && ad.signCount() <= storedSignCount) {
			throw new WebAuthnException("signature counter did not increase (cloned authenticator?)");
		}
		return new Assertion(ad.signCount(), ad.userVerified(), ad.backedUp());
	}

	private void checkClientData(byte[] json, String type, byte[] expectedChallenge) {
		ClientData cd = ClientData.parse(json);
		if (!cd.type().equals(type)) {
			throw new WebAuthnException("clientData: type " + cd.type());
		}
		if (expectedChallenge == null || !MessageDigest.isEqual(fromBase64Url(cd.challenge()), expectedChallenge)) {
			throw new WebAuthnException("clientData: challenge mismatch");
		}
		if (!rp.origins().contains(cd.origin())) {
			throw new WebAuthnException("clientData: origin " + cd.origin() + " not allowed");
		}
		if (cd.crossOrigin()) {
			throw new WebAuthnException("clientData: cross-origin");
		}
	}

	private void checkAuthenticatorData(AuthenticatorData ad, boolean requireUserVerification) {
		if (!MessageDigest.isEqual(ad.rpIdHash(), rpIdHash)) {
			throw new WebAuthnException("authData: RP ID hash mismatch");
		}
		if (!ad.userPresent()) {
			throw new WebAuthnException("authData: user not present");
		}
		if (requireUserVerification && !ad.userVerified()) {
			throw new WebAuthnException("authData: user not verified");
		}
	}

	static byte[] sha256(byte[] b) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(b);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
