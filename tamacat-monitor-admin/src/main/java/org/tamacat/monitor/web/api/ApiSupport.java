package org.tamacat.monitor.web.api;

import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.tamacat.monitor.server.ZabbixServer;
import org.tamacat.monitor.user.Role;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.user.UserScope;
import org.tamacat.monitor.zabbix.HostGroup;
import org.tamacat.monitor.zabbix.ZabbixServerService;

/** JSON reading / writing for the API servlets and filters. */
public final class ApiSupport {

	/** Upper bound of a request body. */
	static final int MAX_BODY = 64 * 1024;

	private ApiSupport() {
	}

	public static void error(HttpServletResponse res, int status, String key) throws IOException {
		write(res, status, Json.createObjectBuilder().add("error", key).build());
	}

	public static void write(HttpServletResponse res, int status, JsonStructure body) throws IOException {
		res.setStatus(status);
		res.setContentType("application/json");
		res.setCharacterEncoding("UTF-8");
		res.setHeader("Cache-Control", "no-store");
		res.getWriter().write(body.toString());
	}

	static JsonObject readObject(HttpServletRequest req) throws IOException {
		if (req.getContentLengthLong() > MAX_BODY) {
			throw new BadRequestException("body too large");
		}
		String ct = req.getContentType();
		if (ct == null || !ct.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
			throw new BadRequestException("content type must be application/json");
		}
		req.setCharacterEncoding("UTF-8");
		try (Reader r = new LimitedReader(req.getReader(), MAX_BODY); JsonReader jr = Json.createReader(r)) {
			return jr.readObject();
		} catch (JsonException | IllegalStateException e) {
			throw new BadRequestException("invalid JSON");
		}
	}

	// ---- field access ----

	static String str(JsonObject o, String key) {
		JsonValue v = o.get(key);
		return v instanceof JsonString s ? s.getString() : null;
	}

	static char[] chars(JsonObject o, String key) {
		String s = str(o, key);
		return s == null ? null : s.toCharArray();
	}

	static boolean bool(JsonObject o, String key, boolean def) {
		JsonValue v = o.get(key);
		return v == JsonValue.TRUE ? true : v == JsonValue.FALSE ? false : def;
	}

	static long longValue(JsonObject o, String key) {
		JsonValue v = o.get(key);
		if (v instanceof JsonNumber n && n.isIntegral()) {
			return n.longValueExact();
		}
		throw new BadRequestException(key + " must be an integer");
	}

	static Set<Long> longSet(JsonObject o, String key) {
		Set<Long> set = new LinkedHashSet<>();
		if (o.get(key) instanceof JsonArray a) {
			for (JsonValue v : a) {
				if (v instanceof JsonNumber n && n.isIntegral()) {
					set.add(n.longValueExact());
				} else {
					throw new BadRequestException(key + " must be integers");
				}
			}
		}
		return set;
	}

	static Set<Role> roles(JsonObject o) {
		Set<Role> set = new LinkedHashSet<>();
		if (o.get("roles") instanceof JsonArray a) {
			for (JsonValue v : a) {
				if (v instanceof JsonString s) {
					set.add(Role.parse(s.getString()).orElseThrow(() -> new BadRequestException("unknown role")));
				}
			}
		}
		return set;
	}

	// ---- representations ----

	static JsonObjectBuilder user(User u) {
		JsonArrayBuilder roles = Json.createArrayBuilder();
		u.roles().stream().sorted().forEach(r -> roles.add(r.name()));
		JsonObjectBuilder b = Json.createObjectBuilder()
				.add("userId", u.userId())
				.add("loginId", u.loginId())
				.add("displayName", u.displayName())
				.add("locale", u.locale())
				.add("enabled", u.enabled())
				.add("mustChangePassword", u.mustChangePassword())
				.add("mfaEnabled", u.mfaEnabled())
				.add("passkeyCount", u.passkeyCount())
				.add("roles", roles);
		addNullable(b, "email", u.email());
		addInstant(b, "lockedUntil", u.lockedUntil());
		addInstant(b, "lastLoginAt", u.lastLoginAt());
		return b;
	}

	static JsonObjectBuilder scope(UserScope s) {
		return Json.createObjectBuilder().add("serverId", s.serverId()).add("hostgroupId", s.hostgroupId())
				.add("hostgroupName", s.hostgroupName()).add("includeSubgroups", s.includeSubgroups());
	}

	static JsonObjectBuilder server(ZabbixServer s) {
		JsonObjectBuilder b = Json.createObjectBuilder()
				.add("serverId", s.serverId())
				.add("name", s.name())
				.add("dbUrl", s.dbUrl())
				.add("readUser", s.readUser())
				.add("hasWritePassword", s.hasWritePassword())
				.add("readOnly", s.readOnly())
				.add("enabled", s.enabled())
				.add("writable", ZabbixServerService.writable(s));
		addNullable(b, "description", s.description());
		addNullable(b, "writeUser", s.writeUser());
		addNullable(b, "zabbixVersion", s.zabbixVersion());
		addNullable(b, "lastStatus", s.lastStatus());
		addInstant(b, "lastCheckedAt", s.lastCheckedAt());
		return b;
	}

	static JsonObjectBuilder hostGroup(HostGroup g) {
		return Json.createObjectBuilder().add("groupId", g.groupId()).add("name", g.name());
	}

	static JsonObject fieldErrors(Map<String, String> errors) {
		JsonObjectBuilder f = Json.createObjectBuilder();
		errors.forEach(f::add);
		return Json.createObjectBuilder().add("error", "error.validation").add("fields", f).build();
	}

	static <T> JsonArray array(Collection<T> items, java.util.function.Function<T, JsonObjectBuilder> fn) {
		JsonArrayBuilder a = Json.createArrayBuilder();
		items.forEach(i -> a.add(fn.apply(i)));
		return a.build();
	}

	private static void addNullable(JsonObjectBuilder b, String key, String value) {
		if (value == null) {
			b.addNull(key);
		} else {
			b.add(key, value);
		}
	}

	private static void addInstant(JsonObjectBuilder b, String key, Instant value) {
		if (value == null) {
			b.addNull(key);
		} else {
			b.add(key, value.toString());
		}
	}

	/** Stops reading after {@code max} characters. */
	private static final class LimitedReader extends java.io.FilterReader {
		private long remaining;

		LimitedReader(Reader in, long max) {
			super(in);
			this.remaining = max;
		}

		@Override
		public int read() throws IOException {
			if (remaining-- <= 0) {
				throw new BadRequestException("body too large");
			}
			return super.read();
		}

		@Override
		public int read(char[] cbuf, int off, int len) throws IOException {
			if (remaining <= 0) {
				throw new BadRequestException("body too large");
			}
			int n = super.read(cbuf, off, (int) Math.min(len, remaining));
			if (n > 0) {
				remaining -= n;
			}
			return n;
		}
	}
}
