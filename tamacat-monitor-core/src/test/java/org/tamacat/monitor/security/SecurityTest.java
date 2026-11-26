package org.tamacat.monitor.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.tamacat.monitor.common.JsonText;
import org.tamacat.monitor.config.AppConfig;

class SecurityTest {

	@Test
	void passwordHashRoundTrip() {
		PasswordHasher h = new PasswordHasher(10_000);
		String stored = h.hash("correct horse battery".toCharArray());
		assertTrue(stored.startsWith("pbkdf2-sha256$10000$"));
		assertTrue(h.verify("correct horse battery".toCharArray(), stored));
		assertFalse(h.verify("correct horse batterY".toCharArray(), stored));
		assertNotEquals(stored, h.hash("correct horse battery".toCharArray()), "salted");
		assertFalse(h.verify("x".toCharArray(), "garbage"));
		assertFalse(h.needsRehash(stored));
		assertTrue(new PasswordHasher(20_000).needsRehash(stored));
	}

	@Test
	void wipeClearsPassword() {
		char[] p = "secret".toCharArray();
		PasswordHasher.wipe(p);
		assertArrayEquals(new char[6], p);
	}

	@Test
	void cipherRoundTripAndPurposeBinding() {
		byte[] key = new byte[32];
		key[0] = 1;
		SecretCipher c = new SecretCipher(key);
		byte[] enc = c.encrypt("zabbix-db-password", "zabbix_servers.db_read_password:1");
		assertEquals("zabbix-db-password", c.decrypt(enc, "zabbix_servers.db_read_password:1"));
		// copied to another row: rejected
		assertThrows(IllegalStateException.class, () -> c.decrypt(enc, "zabbix_servers.db_read_password:2"));
		// tampered: rejected
		enc[enc.length - 1] ^= 1;
		assertThrows(IllegalStateException.class, () -> c.decrypt(enc, "zabbix_servers.db_read_password:1"));
		// other key: rejected
		byte[] enc2 = c.encrypt("x", "p");
		assertThrows(IllegalStateException.class, () -> new SecretCipher(new byte[32]).decrypt(enc2, "p"));
		assertThrows(IllegalArgumentException.class, () -> new SecretCipher(new byte[16]));
	}

	@Test
	void cipherFromConfig() {
		String key = Base64.getEncoder().encodeToString(new byte[32]);
		SecretCipher c = SecretCipher.fromConfig(new AppConfig(Map.of("secret.key", key)));
		assertEquals("v", c.decrypt(c.encrypt("v", "p"), "p"));
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> SecretCipher.fromConfig(new AppConfig(Map.of())));
		assertTrue(e.getMessage().contains("TMON_SECRET_KEY"));
	}

	@Test
	void jsonTextEscapes() {
		assertEquals("{\"a\":\"x\\\"y\\u003c/script\\u003e\",\"n\":1,\"b\":true,\"z\":null}",
				JsonText.object(new java.util.LinkedHashMap<>(Map.of()) {
					private static final long serialVersionUID = 1L;
					{
						put("a", "x\"y</script>");
						put("n", 1);
						put("b", true);
						put("z", null);
					}
				}));
	}
}
