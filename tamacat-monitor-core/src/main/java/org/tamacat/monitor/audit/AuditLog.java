package org.tamacat.monitor.audit;

import java.sql.Types;
import java.time.Clock;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tamacat.dao.BindValue;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.SqlOperations;
import org.tamacat.monitor.common.JsonText;
import org.tamacat.monitor.user.Actor;

/**
 * Writes the audit trail ({@code audit_log}). Details must not contain secrets.
 * Pass the transaction's session so that the audit record commits or rolls back with the change.
 */
public final class AuditLog {

	private static final Logger LOG = LoggerFactory.getLogger("Audit");

	public enum Result {
		SUCCESS, FAILURE, DENIED
	}

	private final Clock clock;

	public AuditLog(Clock clock) {
		this.clock = clock;
	}

	/**
	 * @param serverId   Zabbix server concerned, or null
	 * @param targetType e.g. "user", "zabbix_server", "session"
	 * @param targetId   id or name of the target, or null
	 * @param action     e.g. "create", "update", "delete", "login"
	 * @param detail     non-secret details, or null
	 */
	public void record(SqlOperations db, Actor actor, Long serverId, String targetType, String targetId,
			String action, Result result, Map<String, ?> detail) {
		String json = detail == null || detail.isEmpty() ? null : JsonText.object(detail);
		db.update(PreparedSql.of("insert into audit_log (occurred_at, user_id, login_id, client_ip, server_id, "
				+ "target_type, target_id, action, result, detail) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				clock.instant(),
				actor.userId() > 0 ? (Object) actor.userId() : BindValue.ofNull(Types.BIGINT),
				orNull(actor.loginId()),
				orNull(actor.clientIp()),
				serverId == null ? BindValue.ofNull(Types.BIGINT) : (Object) serverId,
				targetType,
				orNull(targetId),
				action,
				result.name(),
				json == null ? BindValue.ofNull(Types.VARCHAR) : json));
		LOG.info("{} {} {}:{} by {} ({}) server={}", action, result, targetType, targetId, actor.loginId(),
				actor.clientIp(), serverId);
	}

	private static Object orNull(String s) {
		return s == null ? BindValue.ofNull(Types.VARCHAR) : s;
	}
}
