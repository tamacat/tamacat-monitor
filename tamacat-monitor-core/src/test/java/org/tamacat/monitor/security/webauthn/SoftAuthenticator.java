package org.tamacat.monitor.security.webauthn;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A software passkey for tests: it answers navigator.credentials.create / get as an authenticator and a
 * browser would (attestation "none", clientDataJSON, authenticator data, signature). Its fields can be
 * changed to produce invalid responses.
 */
public final class SoftAuthenticator {

	public final int algorithm;
	public final KeyPair keys;
	public final byte[] credentialId;
	public String origin;
	public String rpId;
	public boolean userVerified = true;
	public long counter;
	public boolean crossOrigin;

	public SoftAuthenticator(int algorithm, String rpId, String origin) {
		this.algorithm = algorithm;
		this.rpId = rpId;
		this.origin = origin;
		this.credentialId = WebAuthn.newChallenge();
		try {
			KeyPairGenerator g;
			if (algorithm == CoseKey.ES256) {
				g = KeyPairGenerator.getInstance("EC");
				g.initialize(new ECGenParameterSpec("secp256r1"));
			} else if (algorithm == CoseKey.EDDSA) {
				g = KeyPairGenerator.getInstance("Ed25519");
			} else {
				g = KeyPairGenerator.getInstance("RSA");
				g.initialize(2048);
			}
			keys = g.generateKeyPair();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	public record Created(byte[] clientDataJson, byte[] attestationObject) {
	}

	public record Asserted(byte[] credentialId, byte[] clientDataJson, byte[] authenticatorData, byte[] signature) {
	}

	public Created create(byte[] challenge) {
		byte[] clientData = clientData("webauthn.create", challenge);
		ByteArrayOutputStream ad = new ByteArrayOutputStream();
		ad.writeBytes(authDataHeader(0x40));
		ad.writeBytes(new byte[16]); // AAGUID
		ad.write(credentialId.length >> 8);
		ad.write(credentialId.length & 0xff);
		ad.writeBytes(credentialId);
		ad.writeBytes(coseKey());
		Map<Object, Object> att = new LinkedHashMap<>();
		att.put("fmt", "none");
		att.put("attStmt", Map.of());
		att.put("authData", ad.toByteArray());
		return new Created(clientData, cbor(att));
	}

	public Asserted get(byte[] challenge) {
		counter = counter == 0 ? 0 : counter + 1;
		byte[] clientData = clientData("webauthn.get", challenge);
		byte[] authData = authDataHeader(0);
		return new Asserted(credentialId, clientData, authData, sign(authData, clientData));
	}

	public byte[] sign(byte[] authData, byte[] clientData) {
		try {
			Signature s = Signature.getInstance(algorithm == CoseKey.ES256 ? "SHA256withECDSA"
					: algorithm == CoseKey.EDDSA ? "Ed25519" : "SHA256withRSA");
			s.initSign(keys.getPrivate());
			s.update(authData);
			s.update(WebAuthn.sha256(clientData));
			return s.sign();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private byte[] clientData(String type, byte[] challenge) {
		return ("{\"type\":\"" + type + "\",\"challenge\":\"" + WebAuthn.base64Url(challenge) + "\",\"origin\":\"" + origin
				+ "\",\"crossOrigin\":" + crossOrigin + ",\"other_keys_can_be_added_here\":\"do not compare clientDataJSON against a template\"}")
				.getBytes(StandardCharsets.UTF_8);
	}

	private byte[] authDataHeader(int extraFlags) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		b.writeBytes(WebAuthn.sha256(rpId.getBytes(StandardCharsets.UTF_8)));
		b.write(0x01 | (userVerified ? 0x04 : 0) | extraFlags);
		b.write((int) (counter >>> 24) & 0xff);
		b.write((int) (counter >>> 16) & 0xff);
		b.write((int) (counter >>> 8) & 0xff);
		b.write((int) counter & 0xff);
		return b.toByteArray();
	}

	byte[] coseKey() {
		Map<Object, Object> m = new LinkedHashMap<>();
		if (algorithm == CoseKey.ES256) {
			ECPublicKey k = (ECPublicKey) keys.getPublic();
			m.put(1L, 2L);
			m.put(3L, (long) CoseKey.ES256);
			m.put(-1L, 1L);
			m.put(-2L, fixed(k.getW().getAffineX(), 32));
			m.put(-3L, fixed(k.getW().getAffineY(), 32));
		} else if (algorithm == CoseKey.EDDSA) {
			byte[] spki = keys.getPublic().getEncoded();
			m.put(1L, 1L);
			m.put(3L, (long) CoseKey.EDDSA);
			m.put(-1L, 6L);
			m.put(-2L, Arrays.copyOfRange(spki, spki.length - 32, spki.length));
		} else {
			RSAPublicKey k = (RSAPublicKey) keys.getPublic();
			m.put(1L, 3L);
			m.put(3L, (long) CoseKey.RS256);
			m.put(-1L, unsigned(k.getModulus()));
			m.put(-2L, unsigned(k.getPublicExponent()));
		}
		return cbor(m);
	}

	private static byte[] fixed(BigInteger v, int len) {
		byte[] b = unsigned(v);
		byte[] out = new byte[len];
		System.arraycopy(b, 0, out, len - b.length, b.length);
		return out;
	}

	private static byte[] unsigned(BigInteger v) {
		byte[] b = v.toByteArray();
		return b[0] == 0 && b.length > 1 ? Arrays.copyOfRange(b, 1, b.length) : b;
	}

	// ---- CBOR encoding (test only) ----

	public static byte[] cbor(Object v) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		encode(out, v);
		return out.toByteArray();
	}

	private static void encode(ByteArrayOutputStream out, Object v) {
		if (v instanceof Long n) {
			if (n >= 0) {
				head(out, 0, n);
			} else {
				head(out, 1, -1 - n);
			}
		} else if (v instanceof Integer n) {
			encode(out, n.longValue());
		} else if (v instanceof byte[] b) {
			head(out, 2, b.length);
			out.writeBytes(b);
		} else if (v instanceof String s) {
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			head(out, 3, b.length);
			out.writeBytes(b);
		} else if (v instanceof Map<?, ?> m) {
			head(out, 5, m.size());
			m.forEach((k, x) -> {
				encode(out, k);
				encode(out, x);
			});
		} else if (v instanceof Iterable<?> l) {
			int n = 0;
			for (Object ignored : l) {
				n++;
			}
			head(out, 4, n);
			l.forEach(x -> encode(out, x));
		} else {
			throw new IllegalArgumentException(String.valueOf(v));
		}
	}

	private static void head(ByteArrayOutputStream out, int major, long n) {
		if (n < 24) {
			out.write((major << 5) | (int) n);
		} else if (n < 0x100) {
			out.write((major << 5) | 24);
			out.write((int) n);
		} else if (n < 0x10000) {
			out.write((major << 5) | 25);
			out.write((int) (n >> 8));
			out.write((int) n & 0xff);
		} else {
			out.write((major << 5) | 26);
			for (int i = 3; i >= 0; i--) {
				out.write((int) (n >> (8 * i)) & 0xff);
			}
		}
	}
}
