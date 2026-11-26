package org.tamacat.monitor.common;

import java.util.Map;

/**
 * Minimal JSON writer for flat string maps (audit details), so that tamacat-monitor-core does not need
 * a JSON library. The web layer uses Jakarta JSON-P.
 */
public final class JsonText {

	private JsonText() {
	}

	public static String object(Map<String, ?> map) {
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, ?> e : map.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			string(sb, e.getKey());
			sb.append(':');
			Object v = e.getValue();
			if (v == null) {
				sb.append("null");
			} else if (v instanceof Number || v instanceof Boolean) {
				sb.append(v);
			} else {
				string(sb, v.toString());
			}
		}
		return sb.append('}').toString();
	}

	private static void string(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
	}
}
