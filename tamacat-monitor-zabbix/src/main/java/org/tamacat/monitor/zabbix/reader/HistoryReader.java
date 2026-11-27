package org.tamacat.monitor.zabbix.reader;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tamacat.dao.Conditions;
import org.tamacat.dao.Page;
import org.tamacat.dao.Param;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.dao.Sort;
import org.tamacat.sql.LikeMode;
import org.tamacat.sql.Row;
import org.tamacat.sql.SqlOperations;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.zabbix.ZabbixSchema;
import org.tamacat.monitor.zabbix.access.AccessScope;

/**
 * Reads collected values of items: the history log (values in a period, with a text filter) and
 * graph series (values aggregated into time buckets, from history or trends).
 * <p>
 * Every query is limited to the requested items and a clock range, so it uses the primary key
 * (itemid, clock[, ns]) of the history and trends tables. Items must be readable in the scope.
 */
public final class HistoryReader {

	/** Items in one request. */
	public static final int MAX_ITEMS = 20;
	/** Longest period of the history log and of graphs read from history. */
	public static final Duration MAX_HISTORY_PERIOD = Duration.ofDays(31);
	/** Longest period of graphs read from trends. */
	public static final Duration MAX_TRENDS_PERIOD = Duration.ofDays(2 * 366);
	/** Rows in one page of the history log; offset + limit is bounded by {@link #MAX_ROWS}. */
	public static final int MAX_PAGE = 1000;
	public static final int MAX_ROWS = 10_000;
	/** Buckets of a graph series. */
	public static final int MAX_POINTS = 1000;
	/** Retention assumed for items whose history period is a macro. */
	static final Duration DEFAULT_RETENTION = Duration.ofDays(7);
	static final int MAX_VALUE_LENGTH = 2000;

	/** An item readable by the user. {@code history} / {@code trends} are the item's storage periods as set. */
	public record ItemRef(long itemId, long hostId, String host, String hostName, String name, String key, int valueType,
			String units, String history, String trends) {

		public boolean numeric() {
			return valueType == 0 || valueType == 3;
		}
	}

	/**
	 * History log conditions. {@code text} matches values (and log sources) of text items, {@code exclude}
	 * inverts it; {@code min} / {@code max} bound values of numeric items. Null means "any".
	 */
	public record HistoryQuery(Collection<Long> itemIds, Instant from, Instant to, String text, boolean exclude,
			BigDecimal min, BigDecimal max, Page page) {
	}

	/** A collected value; the log fields are set for log items only. */
	public record HistoryRow(long itemId, Instant clock, int ns, String value, Instant logTime, String source,
			Integer severity, Long logEventId) {
	}

	public enum Source {
		AUTO, HISTORY, TRENDS;

		public static Source of(String s) {
			if (s == null || s.isBlank()) {
				return AUTO;
			}
			try {
				return valueOf(s.strip().toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw ValidationException.of("source", "error.history.source");
			}
		}
	}

	public record GraphQuery(Collection<Long> itemIds, Instant from, Instant to, Source source, int points) {
	}

	/** One bucket: its start, and min / avg / max of the values in it. */
	public record Point(Instant clock, double min, double avg, double max) {
	}

	/** {@code source} is "history" or "trends"; {@code bucket} is the bucket width in seconds. */
	public record Series(ItemRef item, String source, long bucket, List<Point> points) {
	}

	private final ZabbixSchema schema;

	public HistoryReader(ZabbixSchema schema) {
		this.schema = schema;
	}

	// ---- items ----

	/** The requested items in request order; NotFoundException when one is missing or not readable. */
	public List<ItemRef> items(SqlOperations db, AccessScope scope, Collection<Long> itemIds) {
		Set<Long> ids = new java.util.LinkedHashSet<>(itemIds == null ? List.of() : itemIds);
		if (ids.isEmpty() || ids.size() > MAX_ITEMS) {
			throw ValidationException.of("item", "error.history.items");
		}
		var sql = SqlBuilder.of("select i.itemid, i.hostid, h.host, h.name as host_name, i.name, i.key_, i.value_type, i.units, "
				+ "i.history, i.trends from items i join hosts h on h.hostid = i.hostid")
				.where(Param.of("h.status in (0, 1) and h.flags in (0, 4) and i.flags in (0, 4)"), Conditions.in("i.itemid", ids),
						scope.hostCondition("h.hostid"))
				.build();
		Map<Long, ItemRef> found = new LinkedHashMap<>();
		db.queryList(sql, r -> new ItemRef(r.getLong("itemid"), r.getLong("hostid"), r.getString("host"),
				r.getString("host_name"), r.getString("name"), r.getString("key_"), r.getInt("value_type"), r.getString("units"),
				r.getString("history"), r.getString("trends"))).forEach(i -> found.put(i.itemId(), i));
		List<ItemRef> out = new ArrayList<>(ids.size());
		for (long id : ids) {
			ItemRef i = found.get(id);
			if (i == null) {
				throw new NotFoundException("item " + id);
			}
			out.add(i);
		}
		return out;
	}

	// ---- history log ----

	public static void validate(Instant from, Instant to, Duration max) {
		if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).compareTo(max) > 0) {
			throw ValidationException.of("period", "error.period");
		}
	}

	/**
	 * Values of the items in the period, newest first. Each history table is read once for its items
	 * (up to offset + limit rows) and the results are merged.
	 */
	public List<HistoryRow> history(SqlOperations db, List<ItemRef> items, HistoryQuery q) {
		validate(q.from(), q.to(), MAX_HISTORY_PERIOD);
		Page page = q.page();
		if (page.limit() > MAX_PAGE || page.offset() + page.limit() > MAX_ROWS) {
			throw ValidationException.of("limit", "error.history.limit");
		}
		Map<Integer, List<Long>> byType = new LinkedHashMap<>();
		for (ItemRef i : items) {
			if (schema.historyTable(i.valueType()) != null) {
				byType.computeIfAbsent(i.valueType(), k -> new ArrayList<>()).add(i.itemId());
			}
		}
		boolean hasText = q.text() != null && !q.text().isBlank();
		List<HistoryRow> all = new ArrayList<>();
		for (var e : byType.entrySet()) {
			int valueType = e.getKey();
			boolean numeric = valueType == 0 || valueType == 3;
			if (hasText && numeric && !q.exclude()) {
				continue; // a text filter selects text values only
			}
			if (!numeric && (q.min() != null || q.max() != null)) {
				continue; // a value range selects numeric values only
			}
			String table = schema.historyTable(valueType);
			List<Param> where = new ArrayList<>();
			where.add(Conditions.in("itemid", e.getValue()));
			where.add(Conditions.ge("clock", q.from().getEpochSecond()));
			where.add(Conditions.le("clock", q.to().getEpochSecond()));
			if (numeric) {
				if (q.min() != null) {
					where.add(Conditions.ge("value", q.min()));
				}
				if (q.max() != null) {
					where.add(Conditions.le("value", q.max()));
				}
			} else if (hasText) {
				String t = q.text().strip();
				Param match = Conditions.likeIgnoreCase("value", t, LikeMode.CONTAINS);
				if (valueType == 2) {
					match = match.or(Conditions.likeIgnoreCase("source", t, LikeMode.CONTAINS));
				}
				where.add(q.exclude() ? match.group().not() : match.group());
			}
			String cols = valueType == 2 ? "itemid, clock, ns, value, timestamp, source, severity, logeventid"
					: "itemid, clock, ns, value";
			var sql = SqlBuilder.of("select " + cols + " from " + table).where(where)
					.orderBy(Sort.desc("clock").then("ns", Sort.Order.DESC))
					.build(db.dialect(), Page.first(page.offset() + page.limit()));
			all.addAll(db.queryList(sql, r -> historyRow(r, valueType)));
		}
		all.sort(Comparator.comparing(HistoryRow::clock).thenComparingInt(HistoryRow::ns).reversed());
		int from = Math.min(page.offset(), all.size());
		return List.copyOf(all.subList(from, Math.min(all.size(), from + page.limit())));
	}

	private static HistoryRow historyRow(Row r, int valueType) throws SQLException {
		String v = valueType == 0 ? plain(r.getBigDecimal("value")) : r.getString("value");
		if (v != null && v.length() > MAX_VALUE_LENGTH) {
			v = v.substring(0, MAX_VALUE_LENGTH) + "…";
		}
		if (valueType != 2) {
			return new HistoryRow(r.getLong("itemid"), r.getEpochSecond("clock"), r.getInt("ns"), v, null, null, null, null);
		}
		long ts = r.getLong("timestamp");
		return new HistoryRow(r.getLong("itemid"), r.getEpochSecond("clock"), r.getInt("ns"), v,
				ts == 0 ? null : Instant.ofEpochSecond(ts), r.getString("source"), r.getInt("severity"), r.getLong("logeventid"));
	}

	static String plain(BigDecimal d) {
		return d == null ? null : d.stripTrailingZeros().toPlainString();
	}

	// ---- graphs ----

	/** Series of numeric items, aggregated into at most {@code points} buckets of the period. */
	public List<Series> graph(SqlOperations db, List<ItemRef> items, GraphQuery q, Instant now) {
		validate(q.from(), q.to(), q.source() == Source.HISTORY ? MAX_HISTORY_PERIOD : MAX_TRENDS_PERIOD);
		int points = Math.clamp(q.points(), 10, MAX_POINTS);
		long period = Duration.between(q.from(), q.to()).toSeconds();
		List<Series> out = new ArrayList<>(items.size());
		for (ItemRef i : items) {
			if (!i.numeric()) {
				throw ValidationException.of("item", "error.history.notNumeric");
			}
			boolean trends = useTrends(i, q, now);
			long bucket = Math.max(trends ? 3600 : 1, ceilDiv(period, points));
			out.add(new Series(i, trends ? "trends" : "history", bucket,
					trends ? trendPoints(db, i, q, bucket) : historyPoints(db, i, q, bucket)));
		}
		return out;
	}

	/**
	 * Trends when asked, or (auto) when the period starts before the item's history retention or is longer
	 * than {@link #MAX_HISTORY_PERIOD}; history when the item keeps no trends.
	 */
	static boolean useTrends(ItemRef i, GraphQuery q, Instant now) {
		Duration trendKeep = retention(i.trends());
		boolean noTrends = trendKeep != null && trendKeep.isZero();
		return switch (q.source()) {
			case HISTORY -> false;
			case TRENDS -> {
				if (noTrends) {
					throw ValidationException.of("source", "error.history.noTrends");
				}
				yield true;
			}
			case AUTO -> {
				if (noTrends) {
					if (Duration.between(q.from(), q.to()).compareTo(MAX_HISTORY_PERIOD) > 0) {
						throw ValidationException.of("period", "error.period");
					}
					yield false;
				}
				Duration keep = retention(i.history());
				if (keep == null) {
					keep = DEFAULT_RETENTION;
				}
				yield Duration.between(q.from(), q.to()).compareTo(MAX_HISTORY_PERIOD) > 0 || q.from().isBefore(now.minus(keep));
			}
		};
	}

	private static final Pattern PERIOD = Pattern.compile("(\\d+)([smhdw]?)");

	/** A Zabbix time period ("90d", "1w", "3600"); null for macros and other text. */
	static Duration retention(String s) {
		if (s == null) {
			return null;
		}
		Matcher m = PERIOD.matcher(s.strip());
		if (!m.matches()) {
			return null;
		}
		long n = Long.parseLong(m.group(1));
		return switch (m.group(2)) {
			case "m" -> Duration.ofMinutes(n);
			case "h" -> Duration.ofHours(n);
			case "d" -> Duration.ofDays(n);
			case "w" -> Duration.ofDays(n * 7);
			default -> Duration.ofSeconds(n);
		};
	}

	private List<Point> historyPoints(SqlOperations db, ItemRef i, GraphQuery q, long bucket) {
		String table = schema.historyTable(i.valueType());
		// bucket is a computed number, not user text
		var sql = SqlBuilder.of("select floor(clock / " + bucket + ") as b, min(value) as vmin, avg(value) as vavg, "
				+ "max(value) as vmax from " + table)
				.where(Conditions.eq("itemid", i.itemId()), Conditions.ge("clock", q.from().getEpochSecond()),
						Conditions.lt("clock", q.to().getEpochSecond()))
				.append(" group by floor(clock / " + bucket + ") order by b")
				.build();
		return db.queryList(sql, r -> point(r, bucket));
	}

	private static List<Point> trendPoints(SqlOperations db, ItemRef i, GraphQuery q, long bucket) {
		String table = i.valueType() == 0 ? "trends" : "trends_uint";
		var sql = SqlBuilder.of("select floor(clock / " + bucket + ") as b, min(value_min) as vmin, "
				+ "sum(value_avg * num) / sum(num) as vavg, max(value_max) as vmax from " + table)
				.where(Conditions.eq("itemid", i.itemId()), Conditions.ge("clock", q.from().getEpochSecond() / 3600 * 3600),
						Conditions.lt("clock", q.to().getEpochSecond()))
				.append(" group by floor(clock / " + bucket + ") order by b")
				.build();
		return db.queryList(sql, r -> point(r, bucket));
	}

	private static Point point(Row r, long bucket) throws SQLException {
		return new Point(Instant.ofEpochSecond(r.getLong("b") * bucket), num(r.getBigDecimal("vmin")), num(r.getBigDecimal("vavg")),
				num(r.getBigDecimal("vmax")));
	}

	private static double num(BigDecimal d) {
		return d == null ? Double.NaN : d.doubleValue();
	}

	private static long ceilDiv(long a, long b) {
		return (a + b - 1) / b;
	}
}
