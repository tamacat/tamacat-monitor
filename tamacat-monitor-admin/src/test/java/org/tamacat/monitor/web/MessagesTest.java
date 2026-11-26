package org.tamacat.monitor.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

class MessagesTest {

	private static Properties load(String name) throws Exception {
		Properties p = new Properties();
		try (InputStream in = MessagesTest.class.getResourceAsStream("/org/tamacat/monitor/web/" + name)) {
			p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		return p;
	}

	@Test
	void englishAndJapaneseHaveTheSameKeys() throws Exception {
		var en = new TreeSet<>(load("messages.properties").stringPropertyNames());
		var ja = new TreeSet<>(load("messages_ja.properties").stringPropertyNames());
		var missingInJa = new TreeSet<>(en);
		missingInJa.removeAll(ja);
		var extraInJa = new TreeSet<>(ja);
		extraInJa.removeAll(en);
		assertTrue(missingInJa.isEmpty(), "missing in ja: " + missingInJa);
		assertTrue(extraInJa.isEmpty(), "only in ja: " + extraInJa);
	}

	@Test
	void bundlesResolvePerLocale() {
		assertEquals("Sign in", Messages.get("en", "login.title"));
		assertEquals("サインイン", Messages.get("ja", "login.title"));
		assertEquals("unknown.key", Messages.get("ja", "unknown.key"));
		assertFalse(Messages.all("en").isEmpty());
	}
}
