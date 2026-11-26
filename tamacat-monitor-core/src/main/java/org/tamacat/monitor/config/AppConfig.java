package org.tamacat.monitor.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Application configuration.
 * <p>
 * Keys use dotted lower case ({@code db.url}). Sources, later ones override earlier ones:
 * <ol>
 * <li>{@code tamacat-monitor.properties} on the classpath (defaults)</li>
 * <li>the file named by system property {@code tmon.config} or env {@code TMON_CONFIG}</li>
 * <li>environment variables: {@code db.url} is {@code TMON_DB_URL}, {@code db.pool.max-size} is
 * {@code TMON_DB_POOL_MAX_SIZE}</li>
 * <li>system properties: {@code -Dtmon.db.url=...}</li>
 * </ol>
 * Secrets: {@link #secret(String)} reads {@code key}, or the file named by {@code key.file}
 * (for Docker secrets).
 */
public final class AppConfig {

	public static final String ENV_PREFIX = "TMON_";
	public static final String SYSPROP_PREFIX = "tmon.";

	private final Map<String, String> values;

	public AppConfig(Map<String, String> values) {
		Map<String, String> normalized = new HashMap<>();
		values.forEach((k, v) -> normalized.put(normalize(k), v));
		this.values = Map.copyOf(normalized);
	}

	/** Loads from the classpath file, the config file, the environment and system properties. */
	public static AppConfig load() {
		return load(System.getenv(), System.getProperties(), Thread.currentThread().getContextClassLoader());
	}

	static AppConfig load(Map<String, String> env, Properties sysProps, ClassLoader loader) {
		Map<String, String> map = new HashMap<>();
		if (loader != null) {
			try (InputStream in = loader.getResourceAsStream("tamacat-monitor.properties")) {
				if (in != null) {
					putAll(map, read(in));
				}
			} catch (IOException e) {
				throw new IllegalStateException("Cannot read tamacat-monitor.properties", e);
			}
		}
		String file = sysProps.getProperty(SYSPROP_PREFIX + "config", env.get(ENV_PREFIX + "CONFIG"));
		if (file != null && !file.isBlank()) {
			try (InputStream in = Files.newInputStream(Path.of(file))) {
				putAll(map, read(in));
			} catch (IOException e) {
				throw new IllegalStateException("Cannot read config file: " + file, e);
			}
		}
		env.forEach((k, v) -> {
			if (k.startsWith(ENV_PREFIX) && !k.equals(ENV_PREFIX + "CONFIG")) {
				map.put(envToKey(k), v);
			}
		});
		for (String name : sysProps.stringPropertyNames()) {
			if (name.startsWith(SYSPROP_PREFIX) && !name.equals(SYSPROP_PREFIX + "config")) {
				map.put(normalize(name.substring(SYSPROP_PREFIX.length())), sysProps.getProperty(name));
			}
		}
		return new AppConfig(map);
	}

	private static Properties read(InputStream in) throws IOException {
		Properties p = new Properties();
		try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
			p.load(r);
		}
		return p;
	}

	private static void putAll(Map<String, String> map, Properties p) {
		for (String name : p.stringPropertyNames()) {
			map.put(normalize(name), p.getProperty(name).trim());
		}
	}

	/** {@code TMON_DB_POOL_MAX_SIZE} to {@code db.pool.max.size}. */
	static String envToKey(String env) {
		return normalize(env.substring(ENV_PREFIX.length()));
	}

	/**
	 * Keys are compared in a normalized form: lower case, with "-" and "_" read as ".".
	 * So {@code db.pool.max-size} in a file and {@code TMON_DB_POOL_MAX_SIZE} are the same key.
	 */
	static String normalize(String key) {
		return key.toLowerCase(Locale.ROOT).replace('-', '.').replace('_', '.');
	}

	public Optional<String> get(String key) {
		String v = values.get(normalize(key));
		return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
	}

	public String get(String key, String defaultValue) {
		return get(key).orElse(defaultValue);
	}

	public String required(String key) {
		return get(key).orElseThrow(() -> new IllegalStateException(
				"Missing configuration: " + key + " (env " + ENV_PREFIX + key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_') + ")"));
	}

	public int getInt(String key, int defaultValue) {
		return get(key).map(v -> {
			try {
				return Integer.parseInt(v);
			} catch (NumberFormatException e) {
				throw new IllegalStateException("Not an integer: " + key);
			}
		}).orElse(defaultValue);
	}

	public boolean getBoolean(String key, boolean defaultValue) {
		return get(key).map(v -> v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"))
				.orElse(defaultValue);
	}

	/** A duration in seconds. */
	public Duration getSeconds(String key, Duration defaultValue) {
		return get(key).map(v -> Duration.ofSeconds(Long.parseLong(v))).orElse(defaultValue);
	}

	/**
	 * A secret: the value of {@code key}, or the trimmed content of the file named by
	 * {@code key.file}. The file is read on each call, so rotated secrets are picked up.
	 */
	public Optional<String> secret(String key) {
		Optional<String> direct = get(key);
		if (direct.isPresent()) {
			return direct;
		}
		return get(key + ".file").map(f -> {
			try {
				return Files.readString(Path.of(f), StandardCharsets.UTF_8).strip();
			} catch (IOException e) {
				throw new IllegalStateException("Cannot read secret file for " + key, e);
			}
		});
	}

	@Override
	public String toString() {
		return "AppConfig" + values.keySet();
	}
}
