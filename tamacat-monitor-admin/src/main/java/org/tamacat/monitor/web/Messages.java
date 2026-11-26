package org.tamacat.monitor.web;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UI messages (English / Japanese) from {@code org/tamacat/monitor/web/messages*.properties}
 * (UTF-8). The whole bundle is passed to the page scripts, so the server and the browser use
 * the same texts.
 */
public final class Messages {

	private static final String BUNDLE = "org.tamacat.monitor.web.messages";
	private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

	private Messages() {
	}

	public static Map<String, String> all(String locale) {
		return CACHE.computeIfAbsent(locale, l -> {
			ResourceBundle rb = ResourceBundle.getBundle(BUNDLE, Locale.forLanguageTag(l),
					Messages.class.getClassLoader(), ResourceBundle.Control.getNoFallbackControl(
							ResourceBundle.Control.FORMAT_PROPERTIES));
			Map<String, String> map = new TreeMap<>();
			for (String key : rb.keySet()) {
				map.put(key, rb.getString(key));
			}
			return Collections.unmodifiableMap(map);
		});
	}

	public static String get(String locale, String key) {
		return all(locale).getOrDefault(key, key);
	}
}
