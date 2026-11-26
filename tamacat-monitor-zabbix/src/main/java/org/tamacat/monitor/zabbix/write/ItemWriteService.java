package org.tamacat.monitor.zabbix.write;

import java.util.HashSet;

import org.tamacat.monitor.common.ForbiddenException;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.user.Actor;
import org.tamacat.monitor.user.Role;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.zabbix.access.AccessScope;
import org.tamacat.monitor.zabbix.query.ZabbixQueryService;
import org.tamacat.monitor.zabbix.reader.ItemReader;
import org.tamacat.monitor.zabbix.write.ItemWriter.ItemInput;
import org.tamacat.monitor.zabbix.write.ItemWriter.ItemUpdate;
import org.tamacat.monitor.zabbix.write.ZabbixWriteService.Operation;
import org.tamacat.monitor.zabbix.write.ZabbixWriteService.WriteResult;

/**
 * Item changes for users (T-406). The same rule as hosts: server administrators any item; operators
 * only items of hosts whose host groups are all within their permitted host groups; viewers never.
 */
public final class ItemWriteService {

	private final ZabbixQueryService query;
	private final ZabbixWriteService writes;
	private final ItemWriter writer = new ItemWriter();

	public ItemWriteService(ZabbixQueryService query, ZabbixWriteService writes) {
		this.query = query;
		this.writes = writes;
	}

	public ItemReader.ItemDetail get(User user, long serverId, long itemId) {
		return query.onServer(user, serverId, c -> new ItemReader(c.schema()).get(c.db(), c.scope(), itemId))
				.orElseThrow(() -> new NotFoundException("item " + itemId));
	}

	public WriteResult<Long> create(User user, Actor actor, long serverId, ItemInput in, boolean preview) {
		authorizeHost(user, serverId, in.hostId());
		return writes.execute(actor, serverId, new Operation("zabbix_item", in.key(), "create"), preview,
				ctx -> writer.create(ctx, in));
	}

	public WriteResult<Long> update(User user, Actor actor, long serverId, long itemId, ItemUpdate u, boolean preview) {
		ItemReader.ItemDetail item = get(user, serverId, itemId); // 404 when out of scope
		authorizeHost(user, serverId, item.summary().hostId());
		return writes.execute(actor, serverId, new Operation("zabbix_item", u.expectedKey(), "update"), preview, ctx -> {
			writer.update(ctx, itemId, u);
			return itemId;
		});
	}

	/** Deletes an item with its triggers and the graphs left without items (T-408). */
	public WriteResult<Long> delete(User user, Actor actor, long serverId, long itemId, String expectedKey, boolean preview) {
		ItemReader.ItemDetail item = get(user, serverId, itemId); // 404 when out of scope
		authorizeHost(user, serverId, item.summary().hostId());
		return writes.execute(actor, serverId, new Operation("zabbix_item", expectedKey, "delete"), preview, ctx -> {
			writer.delete(ctx, itemId, expectedKey);
			return itemId;
		});
	}

	private void authorizeHost(User user, long serverId, long hostId) {
		if (query.isServerAdmin(user, serverId)) {
			return;
		}
		if (!user.has(Role.OPERATOR)) {
			throw new ForbiddenException();
		}
		var host = query.host(user, serverId, hostId); // 404 when the host is not visible
		AccessScope scope = query.onServer(user, serverId, c -> c.scope());
		if (!(scope instanceof AccessScope.Groups g) || !g.groupIds().containsAll(new HashSet<>(host.summary().groupIds()))) {
			throw new ForbiddenException();
		}
	}
}
