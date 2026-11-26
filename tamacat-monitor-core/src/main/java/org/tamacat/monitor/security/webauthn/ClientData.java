package org.tamacat.monitor.security.webauthn;

import java.nio.charset.StandardCharsets;

/**
 * The fields of {@code clientDataJSON} that are checked: {@code type}, {@code challenge}, {@code origin},
 * {@code crossOrigin}. A small JSON reader is enough because clientDataJSON is a flat object written by the
 * browser (other members, such as {@code tokenBinding} or {@code topOrigin}, are skipped).
 */
record ClientData(String type, String challenge, String origin, boolean crossOrigin) {

	static ClientData parse(byte[] json) {
		Reader r = new Reader(new String(json, StandardCharsets.UTF_8));
		String type = null;
		String challenge = null;
		String origin = null;
		boolean crossOrigin = false;
		r.skipWs();
		r.expect('{');
		r.skipWs();
		if (r.peek() != '}') {
			while (true) {
				r.skipWs();
				String key = r.string();
				r.skipWs();
				r.expect(':');
				r.skipWs();
				switch (key) {
					case "type" -> type = r.string();
					case "challenge" -> challenge = r.string();
					case "origin" -> origin = r.string();
					case "crossOrigin" -> crossOrigin = r.bool();
					default -> r.skipValue(0);
				}
				r.skipWs();
				if (r.peek() == ',') {
					r.pos++;
					continue;
				}
				break;
			}
		}
		r.expect('}');
		r.skipWs();
		if (r.pos != r.s.length()) {
			throw new WebAuthnException("clientData: trailing characters");
		}
		if (type == null || challenge == null || origin == null) {
			throw new WebAuthnException("clientData: type, challenge or origin missing");
		}
		return new ClientData(type, challenge, origin, crossOrigin);
	}

	private static final class Reader {
		final String s;
		int pos;

		Reader(String s) {
			this.s = s;
		}

		char peek() {
			if (pos >= s.length()) {
				throw new WebAuthnException("clientData: truncated");
			}
			return s.charAt(pos);
		}

		void expect(char c) {
			if (peek() != c) {
				throw new WebAuthnException("clientData: expected " + c);
			}
			pos++;
		}

		void skipWs() {
			while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) {
				pos++;
			}
		}

		String string() {
			expect('"');
			StringBuilder sb = new StringBuilder();
			while (true) {
				char c = peek();
				pos++;
				if (c == '"') {
					return sb.toString();
				}
				if (c != '\\') {
					sb.append(c);
					continue;
				}
				char e = peek();
				pos++;
				switch (e) {
					case '"', '\\', '/' -> sb.append(e);
					case 'b' -> sb.append('\b');
					case 'f' -> sb.append('\f');
					case 'n' -> sb.append('\n');
					case 'r' -> sb.append('\r');
					case 't' -> sb.append('\t');
					case 'u' -> {
						if (pos + 4 > s.length()) {
							throw new WebAuthnException("clientData: bad escape");
						}
						sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
						pos += 4;
					}
					default -> throw new WebAuthnException("clientData: bad escape");
				}
			}
		}

		boolean bool() {
			if (s.startsWith("true", pos)) {
				pos += 4;
				return true;
			}
			if (s.startsWith("false", pos)) {
				pos += 5;
				return false;
			}
			throw new WebAuthnException("clientData: expected a boolean");
		}

		void skipValue(int depth) {
			if (depth > 8) {
				throw new WebAuthnException("clientData: nested too deeply");
			}
			char c = peek();
			if (c == '"') {
				string();
			} else if (c == '{' || c == '[') {
				char close = c == '{' ? '}' : ']';
				pos++;
				skipWs();
				if (peek() == close) {
					pos++;
					return;
				}
				while (true) {
					skipWs();
					if (c == '{') {
						string();
						skipWs();
						expect(':');
						skipWs();
					}
					skipValue(depth + 1);
					skipWs();
					if (peek() == ',') {
						pos++;
						continue;
					}
					expect(close);
					return;
				}
			} else {
				int start = pos;
				while (pos < s.length() && ",}] \t\r\n".indexOf(s.charAt(pos)) < 0) {
					pos++;
				}
				String lit = s.substring(start, pos);
				if (!lit.matches("true|false|null|-?[0-9.eE+-]+")) {
					throw new WebAuthnException("clientData: bad value");
				}
			}
		}
	}
}
