package org.tamacat.monitor.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing with PBKDF2-HMAC-SHA256 (JDK only, no extra library).
 * <p>
 * Stored format: {@code pbkdf2-sha256$<iterations>$<salt base64>$<hash base64>}.
 * Verification is constant time; {@link #needsRehash(String)} tells when a stored hash uses
 * fewer iterations than configured, so it can be upgraded at the next login.
 */
public final class PasswordHasher {

	/** OWASP Password Storage Cheat Sheet recommendation for PBKDF2-HMAC-SHA256. */
	public static final int DEFAULT_ITERATIONS = 600_000;

	private static final String PREFIX = "pbkdf2-sha256";
	private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
	private static final int SALT_BYTES = 16;
	private static final int HASH_BITS = 256;

	private final int iterations;
	private final SecureRandom random = new SecureRandom();

	public PasswordHasher() {
		this(DEFAULT_ITERATIONS);
	}

	public PasswordHasher(int iterations) {
		if (iterations < 1000) {
			throw new IllegalArgumentException("iterations too small");
		}
		this.iterations = iterations;
	}

	public String hash(char[] password) {
		byte[] salt = new byte[SALT_BYTES];
		random.nextBytes(salt);
		byte[] hash = derive(password, salt, iterations);
		Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
		return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
	}

	public boolean verify(char[] password, String stored) {
		Parsed p = parse(stored);
		if (p == null) {
			// spend the same time as a real check so unknown formats are not distinguishable
			derive(password, new byte[SALT_BYTES], iterations);
			return false;
		}
		byte[] actual = derive(password, p.salt, p.iterations);
		return MessageDigest.isEqual(actual, p.hash);
	}

	/** Runs one derivation and discards it; used for unknown users to keep login timing uniform. */
	public void dummyVerify(char[] password) {
		derive(password, new byte[SALT_BYTES], iterations);
	}

	public boolean needsRehash(String stored) {
		Parsed p = parse(stored);
		return p == null || p.iterations < iterations;
	}

	private record Parsed(int iterations, byte[] salt, byte[] hash) {
	}

	private static Parsed parse(String stored) {
		if (stored == null) {
			return null;
		}
		String[] parts = stored.split("\\$");
		if (parts.length != 4 || !parts[0].equals(PREFIX)) {
			return null;
		}
		try {
			Base64.Decoder d = Base64.getDecoder();
			return new Parsed(Integer.parseInt(parts[1]), d.decode(parts[2]), d.decode(parts[3]));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static byte[] derive(char[] password, byte[] salt, int iterations) {
		PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
		try {
			return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new IllegalStateException(e);
		} finally {
			spec.clearPassword();
		}
	}

	/** Clears a password array after use. */
	public static void wipe(char[] password) {
		if (password != null) {
			Arrays.fill(password, '\0');
		}
	}
}
