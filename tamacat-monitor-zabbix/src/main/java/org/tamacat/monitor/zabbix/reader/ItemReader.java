package org.tamacat.monitor.zabbix.reader;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * Reads items of hosts (not templates, not prototypes) with their latest value.
 * <p>
 * The latest value is read per item from the history table of its value type using the primary
 * key (itemid, clock, ns), only for the items of the current page, so a page never scans history.
 */
public final class ItemReader {

	/** Upper bound of a page; each item costs one indexed history lookup. */
	public static final int MAX_PAGE = 200;
	/** Text values are cut to this length. */
	static final int MAX_VALUE_LENGTH = 500;

	/** Search conditions; null means "any". */
	public record ItemQuery(Long hostId, String text, Integer status, Page page) {
	}

	/**
	 * @param state      0 normal, 1 not supported
	 * @param lastValue  latest value as text (cut), or null
	 */
	public record ItemSummary(long itemId, long hostId, String host, String hostName, String name, String key, int type,
			int valueType, String delay, String units, int status, int state, String error, Instant lastClock,
			String lastValue) {
	}

	/** Fields for editing an item; {@code editable} is false for items of templates and discovered items. */
	public record ItemDetail(ItemSummary summary, String history, String trends, String description, boolean editable,
			List<HostReader.Tag> tags) {
	}

	private final ZabbixSchema schema;

	public ItemReader(ZabbixSchema schema) {
		this.schema = schema;
	}

	public Optional<ItemDetail> get(SqlOperations db, AccessScope scope, long itemId) {
		var sql = SqlBuilder.of("select i.itemid, i.hostid, h.host, h.name as host_name, i.name, i.key_, i.type, i.value_type, "
				+ "i.delay, i.units, i.status, r.state, r.error, i.history, i.trends, i.description, i.templateid, i.flags "
				+ "from items i join hosts h on h.hostid = i.hostid left join item_rtdata r on r.itemid = i.itemid")
				.where(Param.of("h.status in (0, 1) and h.flags in (0, 4) and i.flags in (0, 4)"), Conditions.eq("i.itemid", itemId),
						scope.hostCondition("h.hostid"))
				.build();
		return db.queryOne(sql, r -> {
			ItemSummary s = row(r);
			return new ItemDetail(s, r.getString("history"), r.getString("trends"), r.getString("description"),
					r.getNullableLong("templateid") == null && r.getInt("flags") == 0, List.of());
		}).map(d -> new ItemDetail(withLatest(db, d.summary()), d.history(), d.trends(), d.description(), d.editable(),
				db.queryList(SqlBuilder.of("select tag, value from item_tag").where(Conditions.eq("itemid", itemId))
						.orderBy(Sort.asc("tag")).build(), r -> new HostReader.Tag(r.getString("tag"), r.getString("value")))));
	}

	public List<ItemSummary> search(SqlOperations db, AccessScope scope, ItemQuery q) {
		if (q.page().limit() > MAX_PAGE) {
			throw new IllegalArgumentException("page too large");
		}
		List<Param> where = new ArrayList<>();
		where.add(Param.of("h.status in (0, 1) and h.flags in (0, 4) and i.flags in (0, 4)"));
		where.add(scope.hostCondition("h.hostid"));
		if (q.hostId() != null) {
			where.add(Conditions.eq("i.hostid", q.hostId()));
		}
		if (q.status() != null) {
			where.add(Conditions.eq("i.status", q.status()));
		}
		if (q.text() != null && !q.text().isBlank()) {
			String t = q.text().strip();
			where.add(Conditions.likeIgnoreCase("i.name", t, LikeMode.CONTAINS)
					.or(Conditions.likeIgnoreCase("i.key_", t, LikeMode.CONTAINS)));
		}
		var sql = SqlBuilder.of("select i.itemid, i.hostid, h.host, h.name as host_name, i.name, i.key_, i.type, i.value_type, "
				+ "i.delay, i.units, i.status, r.state, r.error from items i join hosts h on h.hostid = i.hostid "
				+ "left join item_rtdata r on r.itemid = i.itemid")
				.where(where)
				.orderBy(Sort.asc("h.name").then("i.name", Sort.Order.ASC))
				.build(db.dialect(), q.page());
		List<ItemSummary> rows = db.queryList(sql, ItemReader::row);
		List<ItemSummary> out = new ArrayList<>(rows.size());
		for (ItemSummary i : rows) {
			out.add(withLatest(db, i));
		}
		return out;
	}

	private ItemSummary withLatest(SqlOperations db, ItemSummary i) {
		String table = schema.historyTable(i.valueType());
		if (table == null || i.status() != 0) {
			return i;
		}
		Optional<Latest> latest = db.queryOne(db.dialect().paginate(
				PreparedSql.of("select clock, value from " + table + " where itemid = ? order by clock desc, ns desc", i.itemId()),
				Page.first(1)), r -> new Latest(r.getEpochSecond("clock"), value(r, i.valueType())));
		return latest.map(l -> new ItemSummary(i.itemId(), i.hostId(), i.host(), i.hostName(), i.name(), i.key(), i.type(),
				i.valueType(), i.delay(), i.units(), i.status(), i.state(), i.error(), l.clock(), l.value())).orElse(i);
	}

	private record Latest(Instant clock, String value) {
	}

	private static String value(Row r, int valueType) throws SQLException {
		String v = switch (valueType) {
			case 0 -> {
				BigDecimal d = r.getBigDecimal("value");
				yield d == null ? null : d.stripTrailingZeros().toPlainString();
			}
			default -> r.getString("value");
		};
		return v != null && v.length() > MAX_VALUE_LENGTH ? v.substring(0, MAX_VALUE_LENGTH) + "…" : v;
	}

	private static ItemSummary row(Row r) throws SQLException {
		Integer state = r.getNullableInt("state");
		return new ItemSummary(r.getLong("itemid"), r.getLong("hostid"), r.getString("host"), r.getString("host_name"),
				r.getString("name"), r.getString("key_"), r.getInt("type"), r.getInt("value_type"), r.getString("delay"),
				r.getString("units"), r.getInt("status"), state == null ? 0 : state, r.getString("error"), null, null);
	}
}
