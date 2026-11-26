package org.tamacat.monitor.zabbix.write;

import static org.tamacat.monitor.zabbix.write.HostGroupSets.ordered;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.tamacat.dao.PreparedSql;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.zabbix.ZabbixVersion;
import org.tamacat.monitor.zabbix.write.HostWriter.Tag;

/**
 * Item writes (T-406), equal to what the Zabbix API writes (golden-master tests on 6.0 / 7.0):
 * <ul>
 * <li>create: items (other columns keep their DB defaults, as the API leaves them), item_rtdata (an
 * empty row, values are maintained by the Zabbix server), item_tag; 6.0 sets items.name_upper, 7.0
 * writes item_rtname (resolved name and its upper case) instead.</li>
 * <li>update: changed columns only; tags by difference; 7.0 keeps item_rtname in step.</li>
 * </ul>
 * Supported types: 0 Zabbix agent, 7 Zabbix agent (active), 2 Zabbix trapper. Items of templates,
 * discovered items and the key of an existing item are not changed (DESIGN.md D-06).
 */
public final class ItemWriter {

	public static final Set<Integer> TYPES = Set.of(0, 2, 7);
	private static final Pattern KEY = Pattern.compile("[0-9A-Za-z_.\\-]+(\\[.*\\])?");
	/** 30s, 5m, 1h, 1d, 1w or a number of seconds. */
	private static final Pattern INTERVAL = Pattern.compile("[0-9]{1,9}[smhdw]?");
	private static final Set<Integer> NUMERIC = Set.of(0, 3);

	/**
	 * @param type       0 agent, 7 agent (active), 2 trapper
	 * @param valueType  0 float, 1 character, 2 log, 3 unsigned, 4 text
	 * @param delay      interval for agent items; ignored for trapper (stored as "0")
	 * @param trends     ignored for non-numeric value types (stored as "0")
	 */
	public record ItemInput(long hostId, String name, String key, int type, int valueType, String delay, String history,
			String trends, String units, String description, int status, List<Tag> tags) {
	}

	/**
	 * Update; {@code expectedKey} / {@code expectedName} are what the user saw. A null field is unchanged.
	 */
	public record ItemUpdate(String expectedKey, String expectedName, String name, String delay, String history, String trends,
			String units, String description, Integer status, List<Tag> tags) {
	}

	public long create(WriteContext ctx, ItemInput in) {
		if (!TYPES.contains(in.type())) {
			throw ValidationException.of("type", "error.item.type");
		}
		if (in.valueType() < 0 || in.valueType() > 4) {
			throw ValidationException.of("valueType", "error.item.valueType");
		}
		String name = validName(in.name());
		String key = validKey(in.key());
		boolean trapper = in.type() == 2;
		String delay = trapper ? "0" : validInterval(in.delay(), "delay", false);
		String history = validInterval(in.history() == null ? "31d" : in.history(), "history", true);
		String trends = NUMERIC.contains(in.valueType()) ? validInterval(in.trends() == null ? "365d" : in.trends(), "trends", true) : "0";
		List<Tag> tags = validTags(in.tags());
		String units = validUnits(in.units());
		String description = in.description() == null ? "" : in.description();
		int status = validStatus(in.status());

		requireHost(ctx, in.hostId());
		if (ctx.db().queryOne(PreparedSql.of("select count(*) from items where hostid = ? and key_ = ?", in.hostId(), key),
				r -> r.getInt(1)).orElse(0) > 0) {
			throw new ConflictException("error.item.duplicateKey");
		}
		Long interfaceId = null;
		if (in.type() == 0) {
			interfaceId = ctx.db().queryOne(PreparedSql.of("select interfaceid from interface where hostid = ? and type = 1 "
					+ "and main = 1", in.hostId()), r -> r.getLong(1))
					.orElseThrow(() -> ValidationException.of("type", "error.item.noAgentInterface"));
		}

		long itemId = ctx.nextId("items", "itemid");
		Map<String, Object> row = ordered("itemid", itemId, "hostid", in.hostId(), "name", name, "key_", key, "type", in.type(),
				"value_type", in.valueType(), "delay", delay, "history", history, "trends", trends, "units", units,
				"description", description, "status", status, "flags", 0,
				// TEXT columns have no DB default in MySQL; the API writes empty strings
				"params", "", "query_fields", "", "posts", "", "headers", "");
		if (interfaceId != null) {
			row.put("interfaceid", interfaceId);
		}
		if (v60(ctx)) {
			row.put("name_upper", name.toUpperCase(Locale.ROOT));
		}
		row.putAll(ctx.schema().newItemColumns());
		if (in.type() == 2 && ctx.schema().newTrapperHosts() != null) {
			row.put("trapper_hosts", ctx.schema().newTrapperHosts());
		}
		ctx.insert("items", "itemid", row);
		if (ctx.schema().templateCache()) {
			ctx.insertWithoutId("item_template_cache", ordered("itemid", itemId, "link_hostid", in.hostId()));
		}
		ctx.insert("item_rtdata", "itemid", ordered("itemid", itemId));
		if (!v60(ctx)) {
			ctx.insert("item_rtname", "itemid", ordered("itemid", itemId, "name_resolved", name, "name_resolved_upper",
					name.toUpperCase(Locale.ROOT)));
		}
		for (Tag t : tags) {
			insertTag(ctx, itemId, t);
		}
		return itemId;
	}

	public void update(WriteContext ctx, long itemId, ItemUpdate u) {
		Current cur = load(ctx, itemId);
		if (cur.templateId() != null) {
			throw new ConflictException("error.item.templated");
		}
		if (cur.flags() != 0) {
			throw new ConflictException("error.item.discovered");
		}
		if (!cur.key().equals(u.expectedKey()) || !cur.name().equals(u.expectedName())) {
			throw new ConflictException("error.write.conflict");
		}
		Map<String, Object> changes = new HashMap<>();
		String newName = null;
		if (u.name() != null) {
			String n = validName(u.name());
			if (!n.equals(cur.name())) {
				newName = n;
				changes.put("name", n);
				if (v60(ctx)) {
					changes.put("name_upper", n.toUpperCase(Locale.ROOT));
				}
			}
		}
		if (u.delay() != null && cur.type() != 2) {
			String d = validInterval(u.delay(), "delay", false);
			if (!d.equals(cur.delay())) {
				changes.put("delay", d);
			}
		}
		if (u.history() != null) {
			String h = validInterval(u.history(), "history", true);
			if (!h.equals(cur.history())) {
				changes.put("history", h);
			}
		}
		if (u.trends() != null && NUMERIC.contains(cur.valueType())) {
			String t = validInterval(u.trends(), "trends", true);
			if (!t.equals(cur.trends())) {
				changes.put("trends", t);
			}
		}
		if (u.units() != null) {
			String un = validUnits(u.units());
			if (!un.equals(cur.units())) {
				changes.put("units", un);
			}
		}
		if (u.description() != null && !u.description().equals(cur.description())) {
			changes.put("description", u.description());
		}
		if (u.status() != null && validStatus(u.status()) != cur.status()) {
			changes.put("status", u.status());
		}
		if (!changes.isEmpty()) {
			ctx.update("items", "itemid", itemId, Map.of("key_", u.expectedKey(), "name", u.expectedName()), changes);
		}
		if (newName != null && !v60(ctx)) {
			ctx.update("item_rtname", "itemid", itemId, Map.of(), Map.of("name_resolved", newName, "name_resolved_upper",
					newName.toUpperCase(Locale.ROOT)));
		}
		if (u.tags() != null) {
			updateTags(ctx, itemId, validTags(u.tags()));
		}
	}

	/**
	 * Deletes an item with its triggers and the graphs left without items (T-408, see {@link Deletion}).
	 * Items of templates, discovered items, web scenario items and items with dependent items are refused.
	 */
	public void delete(WriteContext ctx, long itemId, String expectedKey) {
		Current cur = load(ctx, itemId);
		if (cur.templateId() != null) {
			throw new ConflictException("error.item.templated");
		}
		if (cur.flags() != 0) {
			throw new ConflictException("error.item.discovered");
		}
		if (cur.type() == 9) {
			throw new ConflictException("error.item.web");
		}
		if (!cur.key().equals(expectedKey)) {
			throw new ConflictException("error.write.conflict");
		}
		Deletion.itemsBlocker(ctx.db(), ctx.schema(), List.of(itemId), false).ifPresent(key -> {
			throw new ConflictException(key);
		});
		Deletion.deleteItems(ctx, List.of(itemId));
	}

	// ---- parts ----

	private record Current(long hostId, String name, String key, int type, int valueType, String delay, String history,
			String trends, String units, String description, int status, int flags, Long templateId) {
	}

	private static Current load(WriteContext ctx, long itemId) {
		return ctx.db().queryOne(PreparedSql.of("select i.hostid, i.name, i.key_, i.type, i.value_type, i.delay, i.history, "
				+ "i.trends, i.units, i.description, i.status, i.flags, i.templateid from items i join hosts h on h.hostid = i.hostid "
				+ "where i.itemid = ? and h.status in (0, 1) and h.flags = 0", itemId),
				r -> new Current(r.getLong("hostid"), r.getString("name"), r.getString("key_"), r.getInt("type"),
						r.getInt("value_type"), r.getString("delay"), r.getString("history"), r.getString("trends"),
						r.getString("units"), r.getString("description"), r.getInt("status"), r.getInt("flags"),
						r.getNullableLong("templateid")))
				.orElseThrow(() -> new NotFoundException("item " + itemId));
	}

	/** The host of an item, for authorization (null when not found). */
	static Long hostOf(org.tamacat.sql.SqlOperations db, long itemId) {
		return db.queryOne(PreparedSql.of("select hostid from items where itemid = ?", itemId), r -> r.getLong(1)).orElse(null);
	}

	private static void requireHost(WriteContext ctx, long hostId) {
		if (ctx.db().queryOne(PreparedSql.of("select count(*) from hosts where hostid = ? and status in (0, 1) and flags = 0", hostId),
				r -> r.getInt(1)).orElse(0) == 0) {
			throw new NotFoundException("host " + hostId);
		}
	}

	private static boolean v60(WriteContext ctx) {
		return ctx.schema().version() == ZabbixVersion.V6_0;
	}

	private static void insertTag(WriteContext ctx, long itemId, Tag t) {
		ctx.insert("item_tag", "itemtagid", ordered("itemtagid", ctx.nextId("item_tag", "itemtagid"), "itemid", itemId,
				"tag", t.tag(), "value", t.value()));
	}

	private static void updateTags(WriteContext ctx, long itemId, List<Tag> tags) {
		Map<Tag, Long> current = new HashMap<>();
		ctx.db().queryList(PreparedSql.of("select itemtagid, tag, value from item_tag where itemid = ?", itemId),
				r -> Map.entry(new Tag(r.getString("tag"), r.getString("value")), r.getLong("itemtagid")))
				.forEach(e -> current.put(e.getKey(), e.getValue()));
		for (var e : current.entrySet()) {
			if (!tags.contains(e.getKey())) {
				ctx.delete("item_tag", "itemtagid", e.getValue(), Map.of("tag", e.getKey().tag()));
			}
		}
		for (Tag t : tags) {
			if (!current.containsKey(t)) {
				insertTag(ctx, itemId, t);
			}
		}
	}

	// ---- validation ----

	static String validName(String name) {
		String n = name == null ? "" : name.strip();
		// user macros in names are resolved by Zabbix into item_rtname (7.0); tamacat-monitor writes plain names only
		if (n.isEmpty() || n.length() > 255 || n.contains("{$")) {
			throw ValidationException.of("name", "error.item.name");
		}
		return n;
	}

	static String validKey(String key) {
		String k = key == null ? "" : key.strip();
		if (k.length() > 2048 || !KEY.matcher(k).matches() || !balanced(k)) {
			throw ValidationException.of("key", "error.item.key");
		}
		return k;
	}

	private static boolean balanced(String key) {
		int depth = 0;
		boolean quoted = false;
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if (c == '"' && (i == 0 || key.charAt(i - 1) != '\\')) {
				quoted = !quoted;
			} else if (!quoted && c == '[') {
				depth++;
			} else if (!quoted && c == ']') {
				depth--;
				if (depth < 0) {
					return false;
				}
			}
		}
		return depth == 0 && !quoted;
	}

	/** Interval: digits with an optional s/m/h/d/w suffix; "0" allowed only where {@code zeroAllowed}. */
	static String validInterval(String value, String field, boolean zeroAllowed) {
		String v = value == null ? "" : value.strip();
		if (!INTERVAL.matcher(v).matches()) {
			throw ValidationException.of(field, "error.item." + field);
		}
		long digits = Long.parseLong(v.replaceAll("[smhdw]$", ""));
		if (digits == 0 && !zeroAllowed) {
			throw ValidationException.of(field, "error.item." + field);
		}
		return v;
	}

	private static String validUnits(String units) {
		String u = units == null ? "" : units.strip();
		if (u.length() > 255) {
			throw ValidationException.of("units", "error.item.units");
		}
		return u;
	}

	private static int validStatus(int status) {
		if (status != 0 && status != 1) {
			throw ValidationException.of("status", "error.item.status");
		}
		return status;
	}

	private static List<Tag> validTags(List<Tag> tags) {
		if (tags == null) {
			return List.of();
		}
		LinkedHashSet<Tag> out = new LinkedHashSet<>();
		for (Tag t : tags) {
			String tag = t.tag() == null ? "" : t.tag().strip();
			String value = t.value() == null ? "" : t.value();
			if (tag.isEmpty() || tag.length() > 255 || value.length() > 255) {
				throw ValidationException.of("tags", "error.host.tags");
			}
			out.add(new Tag(tag, value));
		}
		return new ArrayList<>(out);
	}
}
