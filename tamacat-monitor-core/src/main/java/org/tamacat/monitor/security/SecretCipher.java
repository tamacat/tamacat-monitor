package org.tamacat.monitor.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.tamacat.monitor.config.AppConfig;

/**
 * AES-256-GCM encryption of secrets stored in the central DB (Zabbix DB passwords).
 * <p>
 * Output: {@code version(1) || nonce(12) || ciphertext+tag}. The associated data binds a value
 * to its purpose (e.g. "zabbix_servers.db_read_password:42"), so an encrypted value copied to
 * another row or column does not decrypt.
 * <p>
 * The master key is 32 bytes, base64 encoded, from {@code secret.key} or {@code secret.key.file}
 * (env {@code TMON_SECRET_KEY} / {@code TMON_SECRET_KEY_FILE}).
 */
public final class SecretCipher {

	private static final byte VERSION = 1;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final SecretKey key;
	private final SecureRandom random = new SecureRandom();

	public SecretCipher(byte[] key) {
		if (key == null || key.length != 32) {
			throw new IllegalArgumentException("secret key must be 32 bytes (AES-256)");
		}
		this.key = new SecretKeySpec(key.clone(), "AES");
	}

	public static SecretCipher fromConfig(AppConfig config) {
		String b64 = config.secret("secret.key").orElseThrow(() -> new IllegalStateException(
				"Missing configuration: secret.key (env TMON_SECRET_KEY or TMON_SECRET_KEY_FILE); "
						+ "generate with: openssl rand -base64 32"));
		byte[] key;
		try {
			key = Base64.getDecoder().decode(b64.strip());
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("secret.key is not valid base64");
		}
		return new SecretCipher(key);
	}

	public byte[] encrypt(String plain, String purpose) {
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			c.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
			byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return ByteBuffer.allocate(1 + NONCE_BYTES + ct.length).put(VERSION).put(nonce).put(ct).array();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("encryption failed", e);
		}
	}

	public String decrypt(byte[] data, String purpose) {
		if (data == null || data.length < 1 + NONCE_BYTES + TAG_BITS / 8 || data[0] != VERSION) {
			throw new IllegalStateException("invalid encrypted value");
		}
		try {
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 1, NONCE_BYTES));
			c.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
			byte[] plain = c.doFinal(data, 1 + NONCE_BYTES, data.length - 1 - NONCE_BYTES);
			return new String(plain, StandardCharsets.UTF_8);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("decryption failed (wrong key or tampered value)");
		}
	}
}
