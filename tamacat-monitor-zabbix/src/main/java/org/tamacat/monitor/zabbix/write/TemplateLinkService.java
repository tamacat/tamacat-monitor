package org.tamacat.monitor.zabbix.write;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.tamacat.dao.Page;
import org.tamacat.monitor.common.ForbiddenException;
import org.tamacat.monitor.user.Actor;
import org.tamacat.monitor.user.Role;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.zabbix.access.AccessScope;
import org.tamacat.monitor.zabbix.query.ZabbixQueryService;
import org.tamacat.monitor.zabbix.reader.HostReader;
import org.tamacat.monitor.zabbix.reader.TemplateReader;
import org.tamacat.monitor.zabbix.write.ZabbixWriteService.Operation;
import org.tamacat.monitor.zabbix.write.ZabbixWriteService.WriteResult;

/**
 * Template links of hosts (T-601). Linking changes only the host, so the rule is that of host updates:
 * server administrators any host, operators hosts whose groups are all within their permitted groups,
 * viewers never. The template list is visible to whoever may change some host of the server.
 */
public final class TemplateLinkService {

	private final ZabbixQueryService query;
	private final ZabbixWriteService writes;
	private final TemplateLinker linker = new TemplateLinker();
	private final TemplateReader reader = new TemplateReader();

	public TemplateLinkService(ZabbixQueryService query, ZabbixWriteService writes) {
		this.query = query;
		this.writes = writes;
	}

	public List<TemplateReader.TemplateSummary> templates(User user, long serverId, String text, Page page) {
		if (!query.isServerAdmin(user, serverId) && !user.has(Role.OPERATOR)) {
			throw new ForbiddenException();
		}
		return query.onServer(user, serverId, c -> reader.search(c.db(), text, page));
	}

	public List<TemplateReader.LinkedTemplate> linked(User user, long serverId, long hostId) {
		query.host(user, serverId, hostId); // 404 when the host is not visible
		return query.onServer(user, serverId, c -> reader.linkedTo(c.db(), hostId));
	}

	public WriteResult<Long> link(User user, Actor actor, long serverId, long hostId, String expectedHost, long templateId,
			boolean preview) {
		authorize(user, serverId, hostId);
		return writes.execute(actor, serverId, new Operation("zabbix_host", expectedHost, "linkTemplate"), preview, ctx -> {
			linker.link(ctx, hostId, expectedHost, templateId);
			return hostId;
		});
	}

	public WriteResult<Long> unlink(User user, Actor actor, long serverId, long hostId, String expectedHost, long templateId,
			boolean clear, boolean preview) {
		authorize(user, serverId, hostId);
		return writes.execute(actor, serverId, new Operation("zabbix_host", expectedHost, clear ? "unlinkClearTemplate" : "unlinkTemplate"),
				preview, ctx -> {
					linker.unlink(ctx, hostId, expectedHost, templateId, clear);
					return hostId;
				});
	}

	private void authorize(User user, long serverId, long hostId) {
		HostReader.HostDetail host = query.host(user, serverId, hostId); // 404 when the host is not visible
		if (query.isServerAdmin(user, serverId)) {
			return;
		}
		if (!user.has(Role.OPERATOR)) {
			throw new ForbiddenException();
		}
		Set<Long> groups = new HashSet<>(host.summary().groupIds());
		AccessScope scope = query.onServer(user, serverId, c -> c.scope());
		if (!(scope instanceof AccessScope.Groups g) || !g.groupIds().containsAll(groups)) {
			throw new ForbiddenException();
		}
	}
}
