package org.tamacat.monitor.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238 with RFC 4226): HMAC-SHA1, 6 digits, 30 second steps, the
 * defaults every authenticator app supports. Secrets are 20 random bytes, exchanged as Base32 (RFC 4648)
 * in an {@code otpauth://} URI.
 */
public final class Totp {

	public static final int DIGITS = 6;
	public static final long STEP_SECONDS = 30;
	private static final int[] POW10 = { 1, 10, 100, 1000, 10000, 100000, 1000000 };
	private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
	private static final SecureRandom RANDOM = new SecureRandom();

	private Totp() {
	}

	public static byte[] newSecret() {
		byte[] b = new byte[20];
		RANDOM.nextBytes(b);
		return b;
	}

	public static long step(Instant t) {
		return Math.floorDiv(t.getEpochSecond(), STEP_SECONDS);
	}

	/** The code of a time step (zero-padded to 6 digits). */
	public static String code(byte[] secret, long step) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(secret, "HmacSHA1"));
			byte[] msg = new byte[8];
			for (int i = 7, s = 0; i >= 0; i--, s += 8) {
				msg[i] = (byte) (step >>> s);
			}
			byte[] h = mac.doFinal(msg);
			int o = h[h.length - 1] & 0x0f;
			int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
			return String.format(Locale.ROOT, "%0" + DIGITS + "d", bin % POW10[DIGITS]);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * The time step that {@code code} matches within {@code window} steps around {@code now} and after
	 * {@code lastStep} (each code is accepted once), or empty. Compared in constant time.
	 */
	public static OptionalLong verify(byte[] secret, String code, Instant now, int window, long lastStep) {
		if (code == null) {
			return OptionalLong.empty();
		}
		String c = code.replace(" ", "").strip();
		if (c.length() != DIGITS || !c.chars().allMatch(Character::isDigit)) {
			return OptionalLong.empty();
		}
		long current = step(now);
		long matched = -1;
		for (long s = current - window; s <= current + window; s++) {
			boolean eq = MessageDigest.isEqual(code(secret, s).getBytes(StandardCharsets.US_ASCII), c.getBytes(StandardCharsets.US_ASCII));
			if (eq && s > lastStep && matched < 0) {
				matched = s;
			}
		}
		return matched < 0 ? OptionalLong.empty() : OptionalLong.of(matched);
	}

	public static String base32(byte[] data) {
		StringBuilder sb = new StringBuilder();
		int buffer = 0;
		int bits = 0;
		for (byte b : data) {
			buffer = (buffer << 8) | (b & 0xff);
			bits += 8;
			while (bits >= 5) {
				sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
				bits -= 5;
			}
		}
		if (bits > 0) {
			sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
		}
		return sb.toString();
	}

	public static byte[] fromBase32(String s) {
		String in = s.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
		byte[] out = new byte[in.length() * 5 / 8];
		int buffer = 0;
		int bits = 0;
		int n = 0;
		for (char ch : in.toCharArray()) {
			int v = BASE32.indexOf(ch);
			if (v < 0) {
				throw new IllegalArgumentException("not Base32");
			}
			buffer = (buffer << 5) | v;
			bits += 5;
			if (bits >= 8) {
				out[n++] = (byte) (buffer >> (bits - 8));
				bits -= 8;
			}
		}
		return out;
	}

	/** {@code otpauth://totp/Issuer:account?secret=...&issuer=Issuer&...} for the QR code of authenticator apps. */
	public static String uri(String issuer, String account, byte[] secret) {
		String label = enc(issuer) + ":" + enc(account);
		return "otpauth://totp/" + label + "?secret=" + base32(secret) + "&issuer=" + enc(issuer) + "&algorithm=SHA1&digits="
				+ DIGITS + "&period=" + STEP_SECONDS;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
