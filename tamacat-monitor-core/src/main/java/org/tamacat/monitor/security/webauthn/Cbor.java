package org.tamacat.monitor.security.webauthn;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal CBOR (RFC 8949) decoder for WebAuthn: attestation objects and COSE keys. Supports unsigned and
 * negative integers (as {@code Long}), byte strings ({@code byte[]}), text strings, arrays, maps, tags
 * (the tag is dropped), {@code true / false / null}. Indefinite lengths and floats are rejected: WebAuthn
 * data is CTAP2 canonical CBOR and uses none of them.
 */
final class Cbor {

	/** Nesting limit against hostile input. */
	private static final int MAX_DEPTH = 16;

	private final byte[] data;
	private int pos;

	private Cbor(byte[] data, int offset) {
		this.data = data;
		this.pos = offset;
	}

	/** Decodes one item that must span all of {@code data}. */
	static Object decode(byte[] data) {
		Cbor c = new Cbor(data, 0);
		Object v = c.read(0);
		if (c.pos != data.length) {
			throw new WebAuthnException("cbor: trailing bytes");
		}
		return v;
	}

	/** Decodes one item at {@code offset}; returns it with the offset after it. */
	static Item decodeAt(byte[] data, int offset) {
		Cbor c = new Cbor(data, offset);
		Object v = c.read(0);
		return new Item(v, c.pos);
	}

	record Item(Object value, int end) {
	}

	private Object read(int depth) {
		if (depth > MAX_DEPTH) {
			throw new WebAuthnException("cbor: nested too deeply");
		}
		int initial = u8();
		int major = initial >>> 5;
		int info = initial & 0x1f;
		if (major == 7) {
			return switch (info) {
				case 20 -> Boolean.FALSE;
				case 21 -> Boolean.TRUE;
				case 22 -> null;
				default -> throw new WebAuthnException("cbor: unsupported simple value " + info);
			};
		}
		long arg = argument(info);
		switch (major) {
			case 0:
				return arg;
			case 1:
				return -1 - arg;
			case 2:
				return bytes(length(arg));
			case 3:
				return new String(bytes(length(arg)), StandardCharsets.UTF_8);
			case 4: {
				int n = length(arg);
				List<Object> list = new ArrayList<>(Math.min(n, 64));
				for (int i = 0; i < n; i++) {
					list.add(read(depth + 1));
				}
				return list;
			}
			case 5: {
				int n = length(arg);
				Map<Object, Object> map = new LinkedHashMap<>();
				for (int i = 0; i < n; i++) {
					Object k = read(depth + 1);
					if (k instanceof byte[]) {
						throw new WebAuthnException("cbor: byte string map key");
					}
					if (map.put(k, read(depth + 1)) != null) {
						throw new WebAuthnException("cbor: duplicate map key");
					}
				}
				return map;
			}
			default: // 6: tag
				return read(depth + 1);
		}
	}

	private long argument(int info) {
		if (info < 24) {
			return info;
		}
		int n = switch (info) {
			case 24 -> 1;
			case 25 -> 2;
			case 26 -> 4;
			case 27 -> 8;
			default -> throw new WebAuthnException("cbor: indefinite or reserved length");
		};
		long v = 0;
		for (int i = 0; i < n; i++) {
			v = (v << 8) | u8();
		}
		if (v < 0) {
			throw new WebAuthnException("cbor: integer out of range");
		}
		return v;
	}

	private int length(long n) {
		if (n > data.length - pos) {
			throw new WebAuthnException("cbor: length beyond the data");
		}
		return (int) n;
	}

	private byte[] bytes(int n) {
		byte[] b = new byte[n];
		System.arraycopy(data, pos, b, 0, n);
		pos += n;
		return b;
	}

	private int u8() {
		if (pos >= data.length) {
			throw new WebAuthnException("cbor: truncated");
		}
		return data[pos++] & 0xff;
	}
}
