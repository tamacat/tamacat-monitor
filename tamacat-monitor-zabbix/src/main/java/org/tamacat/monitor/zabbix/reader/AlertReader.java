package org.tamacat.monitor.zabbix.reader;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.tamacat.dao.Conditions;
import org.tamacat.dao.Page;
import org.tamacat.dao.Param;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.dao.Sort;
import org.tamacat.sql.LikeMode;
import org.tamacat.sql.SqlOperations;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.zabbix.access.AccessScope;

/**
 * Reads the action log ({@code alerts}). A period is required (at most {@link #MAX_PERIOD}),
 * so the query always uses the clock range.
 * <p>
 * Scoped users only see alerts of trigger events on hosts in their host groups; alerts of
 * discovery / autoregistration / internal events are visible to server administrators only.
 */
public final class AlertReader {

	public static final Duration MAX_PERIOD = Duration.ofDays(31);

	/** Search conditions; null means "any". {@code from} and {@code to} are required. */
	public record AlertQuery(Instant from, Instant to, Integer status, Long actionId, String text, Page page) {
	}

	/**
	 * @param status    0 not sent, 1 sent, 2 failed, 3 new
	 * @param alertType 0 message, 1 remote command
	 */
	public record AlertRow(long alertId, Instant clock, Long actionId, String actionName, Long eventId, String eventName,
			Integer severity, String sendTo, String mediaType, String userName, int status, int retries, String error,
			String subject, int alertType) {
	}

	public static void validate(AlertQuery q) {
		if (q.from() == null || q.to() == null || !q.from().isBefore(q.to())
				|| Duration.between(q.from(), q.to()).compareTo(MAX_PERIOD) > 0) {
			throw ValidationException.of("period", "error.period");
		}
	}

	public List<AlertRow> search(SqlOperations db, AccessScope scope, AlertQuery q) {
		validate(q);
		List<Param> where = new ArrayList<>();
		where.add(Conditions.between("a.clock", q.from().getEpochSecond(), q.to().getEpochSecond()));
		if (!scope.isAll()) {
			where.add(Param.of("e.source = 0 and e.object = 0"));
			where.add(scope.triggerCondition("e.objectid"));
		}
		if (q.status() != null) {
			where.add(Conditions.eq("a.status", q.status()));
		}
		if (q.actionId() != null) {
			where.add(Conditions.eq("a.actionid", q.actionId()));
		}
		if (q.text() != null && !q.text().isBlank()) {
			String t = q.text().strip();
			where.add(Conditions.likeIgnoreCase("a.sendto", t, LikeMode.CONTAINS)
					.or(Conditions.likeIgnoreCase("a.subject", t, LikeMode.CONTAINS))
					.or(Conditions.likeIgnoreCase("e.name", t, LikeMode.CONTAINS)));
		}
		var sql = SqlBuilder.of("select a.alertid, a.clock, a.actionid, ac.name as action_name, a.eventid, e.name as event_name, "
				+ "e.severity, a.sendto, mt.name as media_type, u.username, a.status, a.retries, a.error, a.subject, a.alerttype "
				+ "from alerts a left join actions ac on ac.actionid = a.actionid left join events e on e.eventid = a.eventid "
				+ "left join media_type mt on mt.mediatypeid = a.mediatypeid left join users u on u.userid = a.userid")
				.where(where)
				.orderBy(Sort.desc("a.clock").then("a.alertid", Sort.Order.DESC))
				.build(db.dialect(), q.page());
		return db.queryList(sql, r -> new AlertRow(r.getLong("alertid"), r.getEpochSecond("clock"),
				r.getNullableLong("actionid"), r.getString("action_name"), r.getNullableLong("eventid"),
				r.getString("event_name"), r.getNullableInt("severity"), r.getString("sendto"), r.getString("media_type"),
				r.getString("username"), r.getInt("status"), r.getInt("retries"), r.getString("error"),
				cut(r.getString("subject")), r.getInt("alerttype")));
	}

	private static String cut(String s) {
		return s != null && s.length() > 300 ? s.substring(0, 300) + "…" : s;
	}
}
