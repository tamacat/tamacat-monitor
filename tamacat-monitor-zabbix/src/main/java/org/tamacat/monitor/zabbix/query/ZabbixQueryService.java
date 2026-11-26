package org.tamacat.monitor.zabbix.query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tamacat.dao.Page;
import org.tamacat.sql.Database;
import org.tamacat.monitor.common.ForbiddenException;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.server.ZabbixServer;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.user.UserService;
import org.tamacat.monitor.zabbix.HostGroupReader;
import org.tamacat.monitor.zabbix.HostGroupReader.HostGroupSummary;
import org.tamacat.monitor.zabbix.ZabbixSchema;
import org.tamacat.monitor.zabbix.ZabbixServerService;
import org.tamacat.monitor.zabbix.ZabbixVersion;
import org.tamacat.monitor.zabbix.access.AccessResolver;
import org.tamacat.monitor.zabbix.access.AccessResolver.Assignments;
import org.tamacat.monitor.zabbix.access.AccessScope;
import org.tamacat.monitor.zabbix.reader.ActionReader;
import org.tamacat.monitor.zabbix.reader.AlertReader;
import org.tamacat.monitor.zabbix.reader.HostReader;
import org.tamacat.monitor.zabbix.reader.ItemReader;
import org.tamacat.monitor.zabbix.reader.ProblemReader;

/**
 * Read access to the Zabbix servers on behalf of a user (P2).
 * <ul>
 * <li>Only servers the user may access, that are enabled and connected with a supported version.</li>
 * <li>The user's {@link AccessScope} is resolved per server and passed to every reader.</li>
 * <li>Searches over several servers run in parallel on virtual threads with a per-server timeout;
 * a failing server is reported in its own result and does not hide the others.</li>
 * </ul>
 */
public final class ZabbixQueryService {

	private static final Logger LOG = LoggerFactory.getLogger(ZabbixQueryService.class);

	/** One server's part of a multi-server result. {@code errorKey} is set when it failed. */
	public record ServerResult<T>(long serverId, String serverName, String zabbixVersion, List<T> items, String errorKey) {
	}

	/** Everything a reader needs for one server. */
	public record Context(ZabbixServer server, Database db, ZabbixSchema schema, AccessScope scope) {
	}

	private final ZabbixServerService servers;
	private final UserService users;
	private final Duration timeout;

	public ZabbixQueryService(ZabbixServerService servers, UserService users, Duration perServerTimeout) {
		this.servers = servers;
		this.users = users;
		this.timeout = perServerTimeout;
	}

	public Assignments assignments(User user) {
		return new Assignments(users.serverIds(user.userId()), users.scopes(user.userId()));
	}

	/** Servers the user can read now (enabled, connected, supported version). */
	public List<ZabbixServer> accessibleServers(User user) {
		Assignments a = assignments(user);
		return servers.findAll().stream()
				.filter(s -> s.enabled() && ZabbixServerService.STATUS_OK.equals(s.lastStatus())
						&& ZabbixVersion.fromLabel(s.zabbixVersion()).isPresent())
				.filter(s -> AccessResolver.canAccess(user, a, s.serverId()))
				.toList();
	}

	public boolean isServerAdmin(User user, long serverId) {
		return AccessResolver.isServerAdmin(user, assignments(user), serverId);
	}

	// ---- reads ----

	public List<ServerResult<HostGroupSummary>> hostGroups(User user, Collection<Long> serverIds, String text, Page page) {
		return fanOut(user, serverIds, c -> new HostGroupReader(c.schema()).search(c.db(), c.scope(), text, page));
	}

	public HostGroupSummary hostGroup(User user, long serverId, long groupId) {
		return onServer(user, serverId, c -> new HostGroupReader(c.schema()).get(c.db(), c.scope(), groupId))
				.orElseThrow(() -> new NotFoundException("host group " + groupId));
	}

	public List<ServerResult<HostReader.HostSummary>> hosts(User user, Collection<Long> serverIds, HostReader.HostQuery q) {
		return fanOut(user, serverIds, c -> new HostReader(c.schema()).search(c.db(), c.scope(), q));
	}

	public HostReader.HostDetail host(User user, long serverId, long hostId) {
		return onServer(user, serverId, c -> new HostReader(c.schema()).get(c.db(), c.scope(), hostId))
				.orElseThrow(() -> new NotFoundException("host " + hostId));
	}

	public List<ItemReader.ItemSummary> items(User user, long serverId, ItemReader.ItemQuery q) {
		return onServer(user, serverId, c -> new ItemReader(c.schema()).search(c.db(), c.scope(), q));
	}

	public List<ServerResult<ProblemReader.ProblemRow>> problems(User user, Collection<Long> serverIds,
			ProblemReader.ProblemQuery q) {
		return fanOut(user, serverIds, c -> new ProblemReader().search(c.db(), c.scope(), q));
	}

	public List<ServerResult<AlertReader.AlertRow>> alerts(User user, Collection<Long> serverIds, AlertReader.AlertQuery q) {
		AlertReader.validate(q); // before fan-out, so a bad period is a 400 and not a per-server error
		AlertReader reader = new AlertReader();
		return fanOut(user, serverIds, c -> reader.search(c.db(), c.scope(), q));
	}

	/** Actions are global in Zabbix: server administrators only (D-09). */
	public List<ActionReader.ActionSummary> actions(User user, long serverId, Integer eventSource, String text, Page page) {
		requireServerAdmin(user, serverId);
		return onServer(user, serverId, c -> new ActionReader().search(c.db(), eventSource, text, page));
	}

	public ActionReader.ActionDetail action(User user, long serverId, long actionId) {
		requireServerAdmin(user, serverId);
		return onServer(user, serverId, c -> new ActionReader().get(c.db(), actionId))
				.orElseThrow(() -> new NotFoundException("action " + actionId));
	}

	private void requireServerAdmin(User user, long serverId) {
		if (!isServerAdmin(user, serverId)) {
			throw new ForbiddenException();
		}
	}

	// ---- execution ----

	/** Runs {@code fn} on one accessible server. */
	public <T> T onServer(User user, long serverId, Function<Context, T> fn) {
		Assignments a = assignments(user);
		ZabbixServer s = accessibleServers(user).stream().filter(x -> x.serverId() == serverId).findFirst()
				.orElseThrow(() -> new NotFoundException("server " + serverId));
		return fn.apply(context(user, a, s));
	}

	/**
	 * Runs {@code fn} on each accessible server in {@code serverIds} (all accessible servers when empty).
	 */
	public <T> List<ServerResult<T>> fanOut(User user, Collection<Long> serverIds, Function<Context, List<T>> fn) {
		Assignments a = assignments(user);
		List<ZabbixServer> targets = accessibleServers(user).stream()
				.filter(s -> serverIds == null || serverIds.isEmpty() || serverIds.contains(s.serverId()))
				.toList();
		List<ServerResult<T>> results = new ArrayList<>(targets.size());
		// not try-with-resources: close() would wait for a timed-out query; its JDBC query timeout ends it
		ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
		try {
			List<Future<List<T>>> futures = new ArrayList<>(targets.size());
			for (ZabbixServer s : targets) {
				futures.add(ex.submit(() -> fn.apply(context(user, a, s))));
			}
			long deadline = System.nanoTime() + timeout.toNanos();
			for (int i = 0; i < targets.size(); i++) {
				ZabbixServer s = targets.get(i);
				Future<List<T>> f = futures.get(i);
				List<T> items = null;
				String error = null;
				try {
					items = f.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
				} catch (TimeoutException e) {
					f.cancel(true);
					error = "error.server.timeout";
					LOG.warn("Zabbix server {} ({}) timed out", s.serverId(), s.name());
				} catch (ExecutionException e) {
					error = "error.server.query";
					LOG.warn("Zabbix server {} ({}) query failed: {}", s.serverId(), s.name(), e.getCause().toString());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					error = "error.server.timeout";
				}
				results.add(new ServerResult<>(s.serverId(), s.name(), s.zabbixVersion(),
						items == null ? List.of() : items, error));
			}
		} finally {
			ex.shutdownNow();
		}
		return results;
	}

	private Context context(User user, Assignments a, ZabbixServer s) {
		Database db = servers.database(s);
		return new Context(s, db, servers.schema(s), AccessResolver.resolve(user, a, s.serverId(), db));
	}

	public Optional<ZabbixServer> server(long serverId) {
		return servers.find(serverId);
	}
}
