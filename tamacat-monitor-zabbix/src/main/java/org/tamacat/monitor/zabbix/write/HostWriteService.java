package org.tamacat.monitor.zabbix.write;

import java.util.HashSet;
import java.util.Set;

import org.tamacat.monitor.common.ForbiddenException;
import org.tamacat.monitor.user.Actor;
import org.tamacat.monitor.user.Role;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.zabbix.access.AccessScope;
import org.tamacat.monitor.zabbix.query.ZabbixQueryService;
import org.tamacat.monitor.zabbix.reader.HostReader;
import org.tamacat.monitor.zabbix.write.HostWriter.HostInput;
import org.tamacat.monitor.zabbix.write.HostWriter.HostUpdate;
import org.tamacat.monitor.zabbix.write.ZabbixWriteService.Operation;
import org.tamacat.monitor.zabbix.write.ZabbixWriteService.WriteResult;

/**
 * Host changes for users (T-405).
 * <ul>
 * <li>Server administrators: any host of the server.</li>
 * <li>Operators: only when the host's current and new host groups are all within their permitted host
 * groups, so an operator can neither move a host out of another team's group nor into it.</li>
 * <li>Viewers: never.</li>
 * </ul>
 */
public final class HostWriteService {

	private final ZabbixQueryService query;
	private final ZabbixWriteService writes;
	private final HostWriter writer = new HostWriter();

	public HostWriteService(ZabbixQueryService query, ZabbixWriteService writes) {
		this.query = query;
		this.writes = writes;
	}

	public WriteResult<Long> create(User user, Actor actor, long serverId, HostInput in, boolean preview) {
		authorize(user, serverId, in.groupIds());
		return writes.execute(actor, serverId, new Operation("zabbix_host", in.host(), "create"), preview,
				ctx -> writer.create(ctx, in));
	}

	public WriteResult<Long> update(User user, Actor actor, long serverId, long hostId, HostUpdate u, boolean preview) {
		// the host must be visible to the user (404 otherwise) and its current groups permitted
		HostReader.HostDetail current = query.host(user, serverId, hostId);
		Set<Long> groups = new HashSet<>(current.summary().groupIds());
		if (u.groupIds() != null) {
			groups.addAll(u.groupIds());
		}
		authorize(user, serverId, groups);
		return writes.execute(actor, serverId, new Operation("zabbix_host", u.expectedHost(), "update"), preview, ctx -> {
			writer.update(ctx, hostId, u);
			return hostId;
		});
	}

	/** Deletes a host with its items, triggers and graphs (T-408); the same rule as update. */
	public WriteResult<Long> delete(User user, Actor actor, long serverId, long hostId, String expectedHost, boolean preview) {
		HostReader.HostDetail current = query.host(user, serverId, hostId);
		authorize(user, serverId, new HashSet<>(current.summary().groupIds()));
		return writes.execute(actor, serverId, new Operation("zabbix_host", expectedHost, "delete"), preview, ctx -> {
			writer.delete(ctx, hostId, expectedHost);
			return hostId;
		});
	}

	private void authorize(User user, long serverId, Set<Long> groupIds) {
		if (query.isServerAdmin(user, serverId)) {
			return;
		}
		if (!user.has(Role.OPERATOR)) {
			throw new ForbiddenException();
		}
		AccessScope scope = query.onServer(user, serverId, c -> c.scope());
		if (!(scope instanceof AccessScope.Groups g) || groupIds == null || !g.groupIds().containsAll(groupIds)) {
			throw new ForbiddenException();
		}
	}
}
