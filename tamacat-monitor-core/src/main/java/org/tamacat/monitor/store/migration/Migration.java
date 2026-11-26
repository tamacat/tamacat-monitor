package org.tamacat.monitor.store.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One migration script, {@code V<version>__<description>.sql}.
 *
 * @param version     version number
 * @param description description from the file name
 * @param script      script text
 * @param checksum    SHA-256 of the script (hex)
 */
public record Migration(int version, String description, String script, String checksum) {

	private static final Pattern FILE_NAME = Pattern.compile("V(\\d{1,6})__([A-Za-z0-9_]+)\\.sql");

	static Migration of(String fileName, String script) {
		Matcher m = FILE_NAME.matcher(fileName);
		if (!m.matches()) {
			throw new IllegalArgumentException("Invalid migration file name: " + fileName);
		}
		return new Migration(Integer.parseInt(m.group(1)), m.group(2).replace('_', ' '), script, sha256(script));
	}

	List<String> statements() {
		return SqlScriptSplitter.split(script);
	}

	static String sha256(String text) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			String normalized = text.replace("\r\n", "\n");
			return HexFormat.of().formatHex(md.digest(normalized.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
