package org.tamacat.monitor.zabbix.write;

import java.io.Serial;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tamacat.sql.Database;
import org.tamacat.sql.TxOptions;
import org.tamacat.monitor.audit.AuditLog;
import org.tamacat.monitor.audit.AuditLog.Result;
import org.tamacat.monitor.server.ZabbixServer;
import org.tamacat.monitor.user.Actor;
import org.tamacat.monitor.zabbix.ZabbixServerService;

/**
 * Runs a write on a Zabbix DB (T-403).
 * <ul>
 * <li>Only servers that are writable (write account, not read-only, connected, supported version).</li>
 * <li>One operation = one transaction on the write account; ids are allocated inside it.</li>
 * <li>Preview: the same work runs and is rolled back; the recorded changes are returned.</li>
 * <li>Every executed write is recorded in the central audit log with the changed rows
 * (success and failure). Previews are not audited.</li>
 * </ul>
 * Authorization is checked by the caller before calling {@link #execute}.
 */
public final class ZabbixWriteService {

	private static final Logger LOG = LoggerFactory.getLogger(ZabbixWriteService.class);

	/** What is changed, for the audit log. */
	public record Operation(String targetType, String targetName, String action) {
	}

	public record WriteResult<R>(R result, List<Change> changes, boolean preview) {
	}

	private final ZabbixServerService servers;
	private final Database central;
	private final AuditLog audit;
	private final IdAllocator ids = new IdAllocator();

	public ZabbixWriteService(ZabbixServerService servers, Database central, AuditLog audit) {
		this.servers = servers;
		this.central = central;
		this.audit = audit;
	}

	/** Rolls back a preview transaction while carrying its result out. */
	private static final class PreviewRollback extends RuntimeException {
		@Serial
		private static final long serialVersionUID = 1L;
		final transient Object result;
		final transient List<Change> changes;

		PreviewRollback(Object result, List<Change> changes) {
			super(null, null, false, false);
			this.result = result;
			this.changes = changes;
		}
	}

	@SuppressWarnings("unchecked")
	public <R> WriteResult<R> execute(Actor actor, long serverId, Operation op, boolean preview,
			Function<WriteContext, R> work) {
		ZabbixServer server = servers.get(serverId);
		Database db = servers.writeDatabase(server);
		try {
			WriteResult<R> r = db.inTransaction(TxOptions.of(TxOptions.Isolation.READ_COMMITTED), tx -> {
				WriteContext ctx = new WriteContext(tx, server, servers.schema(server), ids);
				R result = work.apply(ctx);
				if (preview) {
					throw new PreviewRollback(result, ctx.changes());
				}
				return new WriteResult<>(result, ctx.changes(), false);
			});
			auditQuietly(actor, serverId, op, Result.SUCCESS, details(r.changes(), null));
			return r;
		} catch (PreviewRollback p) {
			return new WriteResult<>((R) p.result, p.changes, true);
		} catch (RuntimeException e) {
			if (!preview) {
				auditQuietly(actor, serverId, op, Result.FAILURE, details(List.of(), e));
			}
			throw e;
		}
	}

	private static Map<String, Object> details(List<Change> changes, RuntimeException error) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("changes", changes.size());
		for (int i = 0; i < Math.min(changes.size(), 20); i++) {
			Change c = changes.get(i);
			d.put("change" + i, c.operation() + " " + c.table() + "#" + c.id() + " " + c.after());
		}
		if (error != null) {
			d.put("error", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
		}
		return d;
	}

	private void auditQuietly(Actor actor, long serverId, Operation op, Result result, Map<String, Object> detail) {
		try {
			audit.record(central, actor, serverId, op.targetType(), op.targetName(), op.action(), result, detail);
		} catch (RuntimeException e) {
			// the Zabbix change is committed already; never hide that from the caller
			LOG.error("Audit log failed for {} {} on server {}: {}", op.action(), op.targetName(), serverId, e.toString());
		}
	}
}
