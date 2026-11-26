package org.tamacat.monitor.zabbix;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.tamacat.monitor.common.ValidationException;

/**
 * Validation of Zabbix DB JDBC URLs entered by administrators.
 * <p>
 * Only MySQL / PostgreSQL URLs of the form {@code jdbc:<db>://host[:port]/database[?params]} are
 * accepted, and only an allow list of query parameters (TLS and timeouts). Credentials and risky
 * driver options (local infile, deserialization, multi queries, ...) cannot be set through the URL.
 */
public final class JdbcUrlPolicy {

	private static final Pattern URL = Pattern.compile(
			"jdbc:(mysql|postgresql)://[A-Za-z0-9.\\-]{1,253}(:[0-9]{1,5})?/[A-Za-z0-9_\\-]{1,64}(\\?[^#\\s]*)?");

	private static final Set<String> MYSQL_PARAMS = Set.of("sslmode", "connecttimeout", "sockettimeout",
			"tlsversions", "trustcertificatekeystoreurl", "trustcertificatekeystoretype",
			"servertimezone", "connectiontimezone");
	private static final Set<String> POSTGRES_PARAMS = Set.of("ssl", "sslmode", "sslrootcert", "connecttimeout",
			"sockettimeout", "applicationname");

	private JdbcUrlPolicy() {
	}

	public static void validate(String url, String field) {
		if (url == null || url.length() > 500 || !URL.matcher(url).matches()) {
			throw ValidationException.of(field, "error.dbUrl.format");
		}
		int q = url.indexOf('?');
		if (q < 0) {
			return;
		}
		Set<String> allowed = url.startsWith("jdbc:mysql:") ? MYSQL_PARAMS : POSTGRES_PARAMS;
		for (String pair : url.substring(q + 1).split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			String[] kv = pair.split("=", 2);
			String name = kv[0].toLowerCase(Locale.ROOT);
			if (!allowed.contains(name)) {
				throw ValidationException.of(field, "error.dbUrl.param");
			}
			// the driver loads this URL: only a local file (no http: etc., which would make it fetch any address)
			if (name.equals("trustcertificatekeystoreurl")
					&& (kv.length < 2 || !kv[1].toLowerCase(Locale.ROOT).startsWith("file:") || kv[1].contains(".."))) {
				throw ValidationException.of(field, "error.dbUrl.param");
			}
		}
	}
}
