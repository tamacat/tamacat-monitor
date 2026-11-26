package org.tamacat.monitor.zabbix.write;

import org.tamacat.monitor.common.ForbiddenException;
import org.tamacat.monitor.user.Actor;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.zabbix.query.ZabbixQueryService;
import org.tamacat.monitor.zabbix.write.ZabbixWriteService.Operation;
import org.tamacat.monitor.zabbix.write.ZabbixWriteService.WriteResult;

/**
 * Host group changes for users (T-404). Host groups are configuration of the whole server, so only
 * server administrators (SYSTEM_ADMIN, or SERVER_ADMIN of the server) may change them.
 */
public final class HostGroupWriteService {

	private final ZabbixQueryService query;
	private final ZabbixWriteService writes;
	private final HostGroupWriter writer = new HostGroupWriter();

	public HostGroupWriteService(ZabbixQueryService query, ZabbixWriteService writes) {
		this.query = query;
		this.writes = writes;
	}

	public WriteResult<Long> create(User user, Actor actor, long serverId, String name, boolean preview) {
		authorize(user, serverId);
		return writes.execute(actor, serverId, new Operation("zabbix_hostgroup", name, "create"), preview,
				ctx -> writer.create(ctx, name));
	}

	public WriteResult<Long> rename(User user, Actor actor, long serverId, long groupId, String expectedName, String newName,
			boolean preview) {
		authorize(user, serverId);
		return writes.execute(actor, serverId, new Operation("zabbix_hostgroup", expectedName + " -> " + newName, "rename"),
				preview, ctx -> {
					writer.rename(ctx, groupId, expectedName, newName);
					return groupId;
				});
	}

	public WriteResult<Long> delete(User user, Actor actor, long serverId, long groupId, String expectedName, boolean preview) {
		authorize(user, serverId);
		return writes.execute(actor, serverId, new Operation("zabbix_hostgroup", expectedName, "delete"), preview, ctx -> {
			writer.delete(ctx, groupId, expectedName);
			return groupId;
		});
	}

	private void authorize(User user, long serverId) {
		if (!query.isServerAdmin(user, serverId)) {
			throw new ForbiddenException();
		}
	}
}
