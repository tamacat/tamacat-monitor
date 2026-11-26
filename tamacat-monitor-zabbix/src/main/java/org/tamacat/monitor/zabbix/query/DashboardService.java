package org.tamacat.monitor.zabbix.query;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tamacat.dao.Page;
import org.tamacat.sql.Database;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.server.ZabbixServer;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.zabbix.ZabbixServerService;
import org.tamacat.monitor.zabbix.access.AccessResolver;
import org.tamacat.monitor.zabbix.access.AccessScope;
import org.tamacat.monitor.zabbix.query.LastKnownSummaries.LastKnown;
import org.tamacat.monitor.zabbix.query.ZabbixQueryService.Context;
import org.tamacat.monitor.zabbix.query.ZabbixQueryService.ServerResult;
import org.tamacat.monitor.zabbix.reader.DashboardReader;
import org.tamacat.monitor.zabbix.reader.DashboardReader.Summary;
import org.tamacat.monitor.zabbix.reader.DashboardReader.UnavailableHost;
import org.tamacat.monitor.zabbix.reader.ProblemReader;
import org.tamacat.monitor.zabbix.reader.ProblemReader.ProblemRow;

/**
 * Dashboards (T-302 / T-303).
 * <ul>
 * <li>Overview: one card per server the user can see, collected in parallel (via
 * {@link ZabbixQueryService#fanOut}); totals over the cards that answered.</li>
 * <li>Summaries are cached in memory per server and scope for {@code ttl}, so frequent refreshes
 * by many users do not reach the Zabbix DBs.</li>
 * <li>The last full summary of each server is stored in the central DB; server administrators see
 * it, marked as stale, when the server is down or not reachable.</li>
 * <li>General users get the same figures limited to their host groups (the scope).</li>
 * </ul>
 */
public final class DashboardService {

	private static final Logger LOG = LoggerFactory.getLogger(DashboardService.class);

	/**
	 * @param status    "OK", or the server status / "TIMEOUT" / "QUERY_ERROR" when no fresh data
	 * @param summary   figures, or null when nothing is known
	 * @param stale     the summary is the last known one, not current
	 * @param admin     the user administers this server (sees everything on it)
	 */
	public record ServerCard(long serverId, String name, String zabbixVersion, String status, Summary summary,
			Instant collectedAt, boolean stale, boolean admin) {
	}

	public record Overview(List<ServerCard> servers, Summary totals, Instant generatedAt) {
	}

	public record ServerDetail(ServerCard card, List<ProblemRow> problems, List<UnavailableHost> unavailableHosts) {
	}

	private record Cached(Instant at, Summary summary) {
	}

	private final ZabbixQueryService query;
	private final ZabbixServerService servers;
	private final LastKnownSummaries lastKnown;
	private final Duration ttl;
	private final Clock clock;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();
	private final DashboardReader reader = new DashboardReader();

	public DashboardService(ZabbixQueryService query, ZabbixServerService servers, Database central, Duration ttl,
			Clock clock) {
		this.query = query;
		this.servers = servers;
		this.lastKnown = new LastKnownSummaries(central);
		this.ttl = ttl;
		this.clock = clock;
	}

	public Overview overview(User user) {
		var assignments = query.assignments(user);
		List<ServerResult<Cached>> results = query.fanOut(user, Set.of(), c -> List.of(summary(c)));
		List<ServerCard> cards = new ArrayList<>();
		for (ServerResult<Cached> r : results) {
			boolean admin = AccessResolver.isServerAdmin(user, assignments, r.serverId());
			if (r.errorKey() == null && !r.items().isEmpty()) {
				Cached c = r.items().get(0);
				cards.add(new ServerCard(r.serverId(), r.serverName(), r.zabbixVersion(), "OK", c.summary(), c.at(), false, admin));
			} else {
				String status = "error.server.timeout".equals(r.errorKey()) ? "TIMEOUT" : "QUERY_ERROR";
				cards.add(fallback(r.serverId(), r.serverName(), r.zabbixVersion(), status, admin));
			}
		}
		// servers the user administers that are not queryable now (connection error, unsupported version)
		Set<Long> shown = cards.stream().map(ServerCard::serverId).collect(java.util.stream.Collectors.toSet());
		for (ZabbixServer s : servers.findAll()) {
			if (s.enabled() && !shown.contains(s.serverId()) && AccessResolver.isServerAdmin(user, assignments, s.serverId())) {
				String status = s.lastStatus() == null ? "UNKNOWN" : s.lastStatus();
				cards.add(fallback(s.serverId(), s.name(), s.zabbixVersion(), status, true));
			}
		}
		cards.sort(Comparator.comparing(ServerCard::name));
		return new Overview(List.copyOf(cards), totals(cards), clock.instant());
	}

	public ServerDetail server(User user, long serverId) {
		boolean admin = query.isServerAdmin(user, serverId);
		ZabbixServer s = query.server(serverId).orElseThrow(() -> new NotFoundException("server " + serverId));
		try {
			return query.onServer(user, serverId, c -> {
				Cached sum = summary(c);
				List<ProblemRow> problems = new ProblemReader().search(c.db(), c.scope(),
						new ProblemReader.ProblemQuery(null, null, null, null, Page.first(200))).stream()
						.sorted(Comparator.comparingInt(ProblemRow::severity).reversed().thenComparing(ProblemRow::clock,
								Comparator.reverseOrder()))
						.limit(20).toList();
				List<UnavailableHost> unavailable = reader.unavailableHosts(c.db(), c.scope(), Page.first(20));
				return new ServerDetail(new ServerCard(s.serverId(), s.name(), s.zabbixVersion(), "OK", sum.summary(), sum.at(),
						false, admin), problems, unavailable);
			});
		} catch (NotFoundException e) {
			if (!admin) {
				throw e;
			}
			// administered but not queryable: last known figures only
			return new ServerDetail(fallback(s.serverId(), s.name(), s.zabbixVersion(),
					s.lastStatus() == null ? "UNKNOWN" : s.lastStatus(), true), List.of(), List.of());
		}
	}

	/** Cached per server and scope; the full (unscoped) result is also stored as last known. */
	private Cached summary(Context c) {
		String key = c.server().serverId() + ":" + scopeKey(c.scope());
		Instant now = clock.instant();
		Cached hit = cache.get(key);
		if (hit != null && hit.at().plus(ttl).isAfter(now)) {
			return hit;
		}
		Cached fresh = new Cached(now, reader.summary(c.db(), c.scope()));
		cache.put(key, fresh);
		if (c.scope().isAll()) {
			try {
				lastKnown.save(c.server().serverId(), now, fresh.summary());
			} catch (RuntimeException e) {
				LOG.warn("Cannot store last known summary of server {}: {}", c.server().serverId(), e.toString());
			}
		}
		return fresh;
	}

	private ServerCard fallback(long serverId, String name, String version, String status, boolean admin) {
		Optional<LastKnown> lk = admin ? lastKnown.find(serverId) : Optional.empty();
		return new ServerCard(serverId, name, version, status, lk.map(LastKnown::summary).orElse(null),
				lk.map(LastKnown::collectedAt).orElse(null), lk.isPresent(), admin);
	}

	private static String scopeKey(AccessScope scope) {
		return switch (scope) {
			case AccessScope.All a -> "all";
			case AccessScope.None n -> "none";
			case AccessScope.Groups g -> "g" + g.groupIds().stream().sorted().map(String::valueOf)
					.reduce((a, b) -> a + "," + b).orElse("");
		};
	}

	/** Sum over the cards with current (not stale) figures. */
	static Summary totals(List<ServerCard> cards) {
		int[] sev = new int[6];
		int monitored = 0, disabled = 0, maintenance = 0, unavailable = 0, unack = 0, items = 0, unsupported = 0;
		for (ServerCard c : cards) {
			Summary s = c.summary();
			if (s == null || c.stale()) {
				continue;
			}
			monitored += s.hostsMonitored();
			disabled += s.hostsDisabled();
			maintenance += s.hostsInMaintenance();
			unavailable += s.hostsUnavailable();
			unack += s.unacknowledged();
			items += s.itemsEnabled();
			unsupported += s.itemsUnsupported();
			for (int i = 0; i < 6; i++) {
				sev[i] += s.problemsBySeverity()[i];
			}
		}
		return new Summary(monitored, disabled, maintenance, unavailable, sev, unack, items, unsupported);
	}
}
