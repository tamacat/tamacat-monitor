package org.tamacat.monitor.zabbix.reader;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.tamacat.dao.Conditions;
import org.tamacat.dao.Page;
import org.tamacat.dao.Param;
import org.tamacat.dao.PreparedSql;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.dao.Sort;
import org.tamacat.sql.LikeMode;
import org.tamacat.sql.Row;
import org.tamacat.sql.SqlOperations;
import org.tamacat.monitor.zabbix.ZabbixSchema;
import org.tamacat.monitor.zabbix.access.AccessScope;

/**
 * "Latest data": enabled items of monitored hosts with their last two values (the change between them)
 * and tags. As in {@link ItemReader}, values are read per item of the page through the primary key.
 */
public final class LatestDataReader {

	public static final int MAX_PAGE = 200;
	static final int MAX_VALUE_LENGTH = 500;

	/** Search conditions; null means "any". {@code text} matches the item name and key; {@code tag} the tag name. */
	public record LatestQuery(Long groupId, Long hostId, String text, String tag, Page page) {
	}

	/**
	 * @param lastValue  latest value (text cut), or null without data
	 * @param prevValue  the value before it, or null
	 * @param state      0 normal, 1 not supported
	 */
	public record LatestRow(long itemId, long hostId, String host, String hostName, String name, String key, int valueType,
			String units, String delay, int state, String error, Instant lastClock, String lastValue, String prevValue,
			List<HostReader.Tag> tags) {
	}

	private final ZabbixSchema schema;

	public LatestDataReader(ZabbixSchema schema) {
		this.schema = schema;
	}

	public List<LatestRow> search(SqlOperations db, AccessScope scope, LatestQuery q) {
		if (q.page().limit() > MAX_PAGE) {
			throw new IllegalArgumentException("page too large");
		}
		List<Param> where = new ArrayList<>();
		where.add(Param.of("h.status = 0 and h.flags in (0, 4) and i.flags in (0, 4) and i.status = 0"));
		where.add(scope.hostCondition("h.hostid"));
		if (q.hostId() != null) {
			where.add(Conditions.eq("i.hostid", q.hostId()));
		}
		if (q.groupId() != null) {
			where.add(Param.of("exists (select 1 from hosts_groups lg where lg.hostid = h.hostid and lg.groupid = ?)", q.groupId()));
		}
		if (q.text() != null && !q.text().isBlank()) {
			String t = q.text().strip();
			where.add(Conditions.likeIgnoreCase("i.name", t, LikeMode.CONTAINS)
					.or(Conditions.likeIgnoreCase("i.key_", t, LikeMode.CONTAINS)));
		}
		if (q.tag() != null && !q.tag().isBlank()) {
			Param like = Conditions.likeIgnoreCase("lt.tag", q.tag().strip(), LikeMode.CONTAINS);
			where.add(new Param("exists (select 1 from item_tag lt where lt.itemid = i.itemid and " + like.sql() + ")",
					like.values()));
		}
		var sql = SqlBuilder.of("select i.itemid, i.hostid, h.host, h.name as host_name, i.name, i.key_, i.value_type, i.units, "
				+ "i.delay, r.state, r.error from items i join hosts h on h.hostid = i.hostid "
				+ "left join item_rtdata r on r.itemid = i.itemid")
				.where(where)
				.orderBy(Sort.asc("h.name").then("i.name", Sort.Order.ASC))
				.build(db.dialect(), q.page());
		List<LatestRow> rows = db.queryList(sql, LatestDataReader::row);
		Map<Long, List<HostReader.Tag>> tags = tags(db, rows);
		List<LatestRow> out = new ArrayList<>(rows.size());
		for (LatestRow i : rows) {
			String[] values = new String[2];
			Instant[] clock = new Instant[1];
			String table = schema.historyTable(i.valueType());
			if (table != null) {
				int[] n = { 0 };
				db.queryList(db.dialect().paginate(
						PreparedSql.of("select clock, value from " + table + " where itemid = ? order by clock desc, ns desc", i.itemId()),
						Page.first(2)), r -> {
							if (n[0] == 0) {
								clock[0] = r.getEpochSecond("clock");
							}
							values[n[0]++] = value(r, i.valueType());
							return null;
						});
			}
			out.add(new LatestRow(i.itemId(), i.hostId(), i.host(), i.hostName(), i.name(), i.key(), i.valueType(), i.units(),
					i.delay(), i.state(), i.error(), clock[0], values[0], values[1], tags.getOrDefault(i.itemId(), List.of())));
		}
		return out;
	}

	private static Map<Long, List<HostReader.Tag>> tags(SqlOperations db, List<LatestRow> rows) {
		Map<Long, List<HostReader.Tag>> map = new HashMap<>();
		if (rows.isEmpty()) {
			return map;
		}
		var sql = SqlBuilder.of("select itemid, tag, value from item_tag")
				.where(Conditions.in("itemid", rows.stream().map(LatestRow::itemId).toList()))
				.orderBy(Sort.asc("tag").then("value", Sort.Order.ASC))
				.build();
		db.queryList(sql, r -> {
			map.computeIfAbsent(r.getLong("itemid"), k -> new ArrayList<>()).add(new HostReader.Tag(r.getString("tag"),
					r.getString("value")));
			return null;
		});
		return map;
	}

	private static String value(Row r, int valueType) throws SQLException {
		String v = valueType == 0 ? plain(r.getBigDecimal("value")) : r.getString("value");
		return v != null && v.length() > MAX_VALUE_LENGTH ? v.substring(0, MAX_VALUE_LENGTH) + "…" : v;
	}

	private static String plain(BigDecimal d) {
		return d == null ? null : d.stripTrailingZeros().toPlainString();
	}

	private static LatestRow row(Row r) throws SQLException {
		Integer state = r.getNullableInt("state");
		return new LatestRow(r.getLong("itemid"), r.getLong("hostid"), r.getString("host"), r.getString("host_name"),
				r.getString("name"), r.getString("key_"), r.getInt("value_type"), r.getString("units"), r.getString("delay"),
				state == null ? 0 : state, r.getString("error"), null, null, null, List.of());
	}
}
