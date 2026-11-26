package org.tamacat.monitor.security.webauthn;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;

/**
 * A credential public key in COSE format (RFC 9053), limited to the algorithms passkeys use:
 * ES256 (-7, EC2 P-256), EdDSA (-8, OKP Ed25519) and RS256 (-257, RSA of at least 2048 bits).
 */
public record CoseKey(int algorithm, PublicKey publicKey) {

	public static final int ES256 = -7;
	public static final int EDDSA = -8;
	public static final int RS256 = -257;

	/** Ed25519 SubjectPublicKeyInfo header; the 32-byte key follows. */
	private static final byte[] ED25519_SPKI = { 0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00 };

	/** Parses the COSE key bytes as stored for a passkey. */
	public static CoseKey parse(byte[] cose) {
		return of(Cbor.decode(cose));
	}

	static CoseKey of(Object cbor) {
		if (!(cbor instanceof Map<?, ?> m)) {
			throw new WebAuthnException("cose: not a map");
		}
		long kty = num(m, 1L);
		long alg = num(m, 3L);
		try {
			if (alg == ES256 && kty == 2) {
				if (num(m, -1L) != 1) {
					throw new WebAuthnException("cose: EC2 curve is not P-256");
				}
				byte[] x = bin(m, -2L);
				byte[] y = bin(m, -3L);
				if (x.length != 32 || y.length != 32) {
					throw new WebAuthnException("cose: bad P-256 point");
				}
				AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
				p.init(new ECGenParameterSpec("secp256r1"));
				ECPublicKeySpec spec = new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)),
						p.getParameterSpec(ECParameterSpec.class));
				return new CoseKey(ES256, KeyFactory.getInstance("EC").generatePublic(spec));
			}
			if (alg == EDDSA && kty == 1) {
				if (num(m, -1L) != 6) {
					throw new WebAuthnException("cose: OKP curve is not Ed25519");
				}
				byte[] x = bin(m, -2L);
				if (x.length != 32) {
					throw new WebAuthnException("cose: bad Ed25519 key");
				}
				byte[] spki = new byte[ED25519_SPKI.length + 32];
				System.arraycopy(ED25519_SPKI, 0, spki, 0, ED25519_SPKI.length);
				System.arraycopy(x, 0, spki, ED25519_SPKI.length, 32);
				return new CoseKey(EDDSA, KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki)));
			}
			if (alg == RS256 && kty == 3) {
				BigInteger n = new BigInteger(1, bin(m, -1L));
				BigInteger e = new BigInteger(1, bin(m, -2L));
				if (n.bitLength() < 2048) {
					throw new WebAuthnException("cose: RSA key shorter than 2048 bits");
				}
				return new CoseKey(RS256, KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e)));
			}
		} catch (GeneralSecurityException e) {
			throw new WebAuthnException("cose: invalid key", e);
		}
		throw new WebAuthnException("cose: unsupported algorithm " + alg + " / key type " + kty);
	}

	/** Verifies a WebAuthn signature (ES256: ASN.1 DER, as authenticators produce it). */
	public boolean verify(byte[] signed, byte[] signature) {
		try {
			Signature s = Signature.getInstance(switch (algorithm) {
				case ES256 -> "SHA256withECDSA";
				case EDDSA -> "Ed25519";
				default -> "SHA256withRSA";
			});
			s.initVerify(publicKey);
			s.update(signed);
			return s.verify(signature);
		} catch (GeneralSecurityException | RuntimeException e) {
			return false; // malformed signature
		}
	}

	private static long num(Map<?, ?> m, Long key) {
		if (m.get(key) instanceof Long v) {
			return v;
		}
		throw new WebAuthnException("cose: parameter " + key + " missing");
	}

	private static byte[] bin(Map<?, ?> m, Long key) {
		if (m.get(key) instanceof byte[] v) {
			return v;
		}
		throw new WebAuthnException("cose: parameter " + key + " missing");
	}
}
