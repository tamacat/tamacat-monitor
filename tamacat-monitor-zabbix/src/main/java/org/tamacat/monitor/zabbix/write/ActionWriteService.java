package org.tamacat.monitor.zabbix.write;

import org.tamacat.monitor.common.ForbiddenException;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.user.Actor;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.zabbix.query.ZabbixQueryService;
import org.tamacat.monitor.zabbix.reader.ActionReader;
import org.tamacat.monitor.zabbix.write.ActionWriter.ActionInput;
import org.tamacat.monitor.zabbix.write.ZabbixWriteService.Operation;
import org.tamacat.monitor.zabbix.write.ZabbixWriteService.WriteResult;

/**
 * Trigger action changes (T-407). Actions are global on a Zabbix server, so only server
 * administrators (SYSTEM_ADMIN, or SERVER_ADMIN of the server) may read the form or change them (D-09).
 */
public final class ActionWriteService {

	private final ZabbixQueryService query;
	private final ZabbixWriteService writes;
	private final ActionWriter writer = new ActionWriter();

	public ActionWriteService(ZabbixQueryService query, ZabbixWriteService writes) {
		this.query = query;
		this.writes = writes;
	}

	/** The stored definition for the edit form; {@code reason} is set when tamacat-monitor cannot change it. */
	public ActionWriter.Editable editable(User user, long serverId, long actionId) {
		authorize(user, serverId);
		return query.onServer(user, serverId, c -> ActionWriter.editable(c.db(), c.schema().version(), actionId))
				.orElseThrow(() -> new NotFoundException("action " + actionId));
	}

	public ActionReader.Choices choices(User user, long serverId) {
		authorize(user, serverId);
		return query.onServer(user, serverId, c -> new ActionReader().choices(c.db()));
	}

	public WriteResult<Long> create(User user, Actor actor, long serverId, ActionInput in, boolean preview) {
		authorize(user, serverId);
		return writes.execute(actor, serverId, new Operation("zabbix_action", in.name(), "create"), preview,
				ctx -> writer.create(ctx, in));
	}

	public WriteResult<Long> update(User user, Actor actor, long serverId, long actionId, String expectedName, ActionInput in,
			boolean preview) {
		authorize(user, serverId);
		return writes.execute(actor, serverId, new Operation("zabbix_action", expectedName, "update"), preview, ctx -> {
			writer.update(ctx, actionId, expectedName, in);
			return actionId;
		});
	}

	public WriteResult<Long> delete(User user, Actor actor, long serverId, long actionId, String expectedName, boolean preview) {
		authorize(user, serverId);
		return writes.execute(actor, serverId, new Operation("zabbix_action", expectedName, "delete"), preview, ctx -> {
			writer.delete(ctx, actionId, expectedName);
			return actionId;
		});
	}

	private void authorize(User user, long serverId) {
		if (!query.isServerAdmin(user, serverId)) {
			throw new ForbiddenException();
		}
	}
}
