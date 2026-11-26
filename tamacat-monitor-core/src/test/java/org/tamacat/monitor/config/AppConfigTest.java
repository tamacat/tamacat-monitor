package org.tamacat.monitor.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigTest {

	@TempDir
	Path dir;

	@Test
	void environmentOverridesFileAndSystemPropertiesOverrideEnvironment() throws Exception {
		Path file = dir.resolve("tamacat-monitor.properties");
		Files.writeString(file, "db.url=jdbc:mysql://file/tmon\ndb.pool.max-size=5\ndb.user=fromfile\n");
		Map<String, String> env = Map.of(
				"TMON_CONFIG", file.toString(),
				"TMON_DB_URL", "jdbc:mysql://env/tmon",
				"TMON_DB_POOL_MAX_SIZE", "20",
				"PATH", "ignored");
		Properties sys = new Properties();
		sys.setProperty("tmon.db.user", "fromsys");

		AppConfig c = AppConfig.load(env, sys, null);
		assertEquals("jdbc:mysql://env/tmon", c.required("db.url"));
		assertEquals(20, c.getInt("db.pool.max-size", 1));
		assertEquals("fromsys", c.required("db.user"));
		assertTrue(c.get("path").isEmpty());
	}

	@Test
	void secretIsReadFromFile() throws Exception {
		Path secret = dir.resolve("pw.txt");
		Files.writeString(secret, "s3cret\n");
		AppConfig c = AppConfig.load(Map.of("TMON_DB_PASSWORD_FILE", secret.toString()), new Properties(), null);
		assertEquals("s3cret", c.secret("db.password").orElseThrow());
		assertTrue(c.secret("other.password").isEmpty());
	}

	@Test
	void missingRequiredKeyNamesTheEnvironmentVariable() {
		AppConfig c = new AppConfig(Map.of());
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> c.required("db.url"));
		assertTrue(e.getMessage().contains("TMON_DB_URL"));
		assertEquals(Duration.ofSeconds(7), c.getSeconds("x", Duration.ofSeconds(7)));
		assertEquals(true, new AppConfig(Map.of("flag", "TRUE")).getBoolean("flag", false));
	}
}
