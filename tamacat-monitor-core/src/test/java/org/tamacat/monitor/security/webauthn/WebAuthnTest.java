package org.tamacat.monitor.security.webauthn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebAuthnTest {

	static final String RP = "monitor.example.com";
	static final String ORIGIN = "https://monitor.example.com";
	private final WebAuthn webauthn = new WebAuthn(new WebAuthn.RelyingParty(RP, "tamacat-monitor", Set.of(ORIGIN)));

	private byte[] register(SoftAuthenticator a) {
		byte[] challenge = WebAuthn.newChallenge();
		var created = a.create(challenge);
		var c = webauthn.verifyRegistration(created.clientDataJson(), created.attestationObject(), challenge, true);
		assertArrayEquals(a.credentialId, c.credentialId());
		assertEquals(a.algorithm, c.algorithm());
		return c.publicKey();
	}

	@ParameterizedTest
	@ValueSource(ints = { CoseKey.ES256, CoseKey.EDDSA, CoseKey.RS256 })
	void registersAndSignsIn(int algorithm) {
		SoftAuthenticator a = new SoftAuthenticator(algorithm, RP, ORIGIN);
		byte[] key = register(a);
		byte[] challenge = WebAuthn.newChallenge();
		var r = a.get(challenge);
		var ok = webauthn.verifyAssertion(r.clientDataJson(), r.authenticatorData(), r.signature(), challenge, key, 0, true);
		assertTrue(ok.userVerified());
		assertEquals(0, ok.signCount());
	}

	@Test
	void registrationChecks() {
		SoftAuthenticator a = new SoftAuthenticator(CoseKey.ES256, RP, ORIGIN);
		byte[] challenge = WebAuthn.newChallenge();
		var created = a.create(challenge);
		assertRejected("challenge mismatch", () -> webauthn.verifyRegistration(created.clientDataJson(), created.attestationObject(),
				WebAuthn.newChallenge(), true));

		a.origin = "https://evil.example.net";
		var evil = a.create(challenge);
		assertRejected("origin", () -> webauthn.verifyRegistration(evil.clientDataJson(), evil.attestationObject(), challenge, true));

		a.origin = ORIGIN;
		a.rpId = "example.com";
		var otherRp = a.create(challenge);
		assertRejected("RP ID hash", () -> webauthn.verifyRegistration(otherRp.clientDataJson(), otherRp.attestationObject(), challenge, true));

		a.rpId = RP;
		a.userVerified = false;
		var noUv = a.create(challenge);
		assertRejected("not verified", () -> webauthn.verifyRegistration(noUv.clientDataJson(), noUv.attestationObject(), challenge, true));

		a.userVerified = true;
		a.crossOrigin = true;
		var cross = a.create(challenge);
		assertRejected("cross-origin", () -> webauthn.verifyRegistration(cross.clientDataJson(), cross.attestationObject(), challenge, true));

		// an assertion is not a registration
		a.crossOrigin = false;
		var asserted = a.get(challenge);
		assertRejected("type", () -> webauthn.verifyRegistration(asserted.clientDataJson(), created.attestationObject(), challenge, true));
	}

	@Test
	void assertionChecks() {
		SoftAuthenticator a = new SoftAuthenticator(CoseKey.ES256, RP, ORIGIN);
		byte[] key = register(a);
		byte[] challenge = WebAuthn.newChallenge();
		var r = a.get(challenge);

		byte[] tampered = r.authenticatorData().clone();
		tampered[32] |= 0x10; // flags changed after signing
		assertRejected("signature", () -> webauthn.verifyAssertion(r.clientDataJson(), tampered, r.signature(), challenge, key, 0, true));

		SoftAuthenticator other = new SoftAuthenticator(CoseKey.ES256, RP, ORIGIN);
		var foreign = other.get(challenge);
		assertRejected("signature", () -> webauthn.verifyAssertion(foreign.clientDataJson(), foreign.authenticatorData(),
				foreign.signature(), challenge, key, 0, true));

		assertRejected("challenge", () -> webauthn.verifyAssertion(r.clientDataJson(), r.authenticatorData(), r.signature(),
				WebAuthn.newChallenge(), key, 0, true));

		a.userVerified = false;
		var noUv = a.get(challenge);
		assertRejected("not verified", () -> webauthn.verifyAssertion(noUv.clientDataJson(), noUv.authenticatorData(), noUv.signature(),
				challenge, key, 0, true));
		// the second step after the password does not need user verification
		assertFalse(webauthn.verifyAssertion(noUv.clientDataJson(), noUv.authenticatorData(), noUv.signature(), challenge, key, 0, false)
				.userVerified());
	}

	@Test
	void signatureCounterMustIncrease() {
		SoftAuthenticator a = new SoftAuthenticator(CoseKey.EDDSA, RP, ORIGIN);
		byte[] key = register(a);
		a.counter = 9;
		byte[] challenge = WebAuthn.newChallenge();
		var r = a.get(challenge); // counter 10
		assertEquals(10, webauthn.verifyAssertion(r.clientDataJson(), r.authenticatorData(), r.signature(), challenge, key, 9, true)
				.signCount());
		assertRejected("counter", () -> webauthn.verifyAssertion(r.clientDataJson(), r.authenticatorData(), r.signature(), challenge,
				key, 10, true));
	}

	@Test
	void malformedInputIsRejected() {
		assertRejected("trailing", () -> Cbor.decode(new byte[] { 0x01, 0x02 }));
		assertRejected("beyond the data", () -> Cbor.decode(new byte[] { 0x58, 0x20, 0x00 }));
		assertRejected("truncated", () -> Cbor.decode(new byte[] { 0x19, 0x01 }));
		assertRejected("indefinite", () -> Cbor.decode(new byte[] { (byte) 0x9f, (byte) 0xff }));
		byte[] deep = new byte[40];
		java.util.Arrays.fill(deep, (byte) 0x81); // [[[[...
		assertRejected("nested", () -> Cbor.decode(deep));
		assertEquals(List.of(), Cbor.decode(SoftAuthenticator.cbor(List.of())));
		assertRejected("duplicate", () -> Cbor.decode(new byte[] { (byte) 0xa2, 0x01, 0x01, 0x01, 0x02 }));
		assertRejected("unsupported", () -> CoseKey.of(Map.of(1L, 2L, 3L, -35L)));
		assertRejected("base64url", () -> WebAuthn.fromBase64Url("***"));
		assertRejected("clientData", () -> ClientData.parse("{\"type\":\"webauthn.get\"}".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void clientDataSkipsOtherMembers() {
		var cd = ClientData.parse(("{ \"tokenBinding\": {\"status\": \"present\", \"id\": [1, 2]}, \"type\": \"webauthn.get\", "
				+ "\"challenge\": \"YWJj\", \"origin\": \"https:\\/\\/a.example\\u0041\", \"crossOrigin\": false, \"n\": -1.5e3 }")
				.getBytes(StandardCharsets.UTF_8));
		assertEquals("webauthn.get", cd.type());
		assertEquals("https://a.exampleA", cd.origin());
		assertFalse(cd.crossOrigin());
	}

	private static void assertRejected(String reason, org.junit.jupiter.api.function.Executable e) {
		WebAuthnException ex = assertThrows(WebAuthnException.class, e);
		assertTrue(ex.getMessage().contains(reason) || reason.isEmpty(), reason + " <> " + ex.getMessage());
	}
}
