package org.tamacat.monitor.zabbix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tamacat.dao.Page;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Database;
import org.tamacat.monitor.user.Role;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.user.UserScope;
import org.tamacat.monitor.zabbix.access.AccessResolver;
import org.tamacat.monitor.zabbix.access.AccessResolver.Assignments;
import org.tamacat.monitor.zabbix.access.AccessScope;
import org.tamacat.monitor.zabbix.reader.ActionReader;
import org.tamacat.monitor.zabbix.reader.AlertReader;
import org.tamacat.monitor.zabbix.reader.HostReader;
import org.tamacat.monitor.zabbix.reader.ItemReader;
import org.tamacat.monitor.zabbix.reader.ProblemReader;

/**
 * Runs every reader against real Zabbix schemas (T-201).
 * <p>
 * Enabled by environment variables pointing at the test containers of docker/README.md, e.g.
 * <pre>
 * TMON_IT_ZABBIX60_URL=jdbc:mysql://127.0.0.1:13360/zabbix
 * TMON_IT_ZABBIX70_URL=jdbc:mysql://127.0.0.1:13370/zabbix
 * TMON_IT_ZABBIX80_URL=jdbc:mysql://127.0.0.1:13380/zabbix
 * TMON_IT_USER=zabbix  TMON_IT_PASSWORD=zabbix
 * </pre>
 * The test inserts fixture rows with ids from 990000 (a sub group, a host, an alert):
 * <b>use only against throw-away test databases.</b>
 */
class RealSchemaTest {

	static Stream<Arguments> servers() {
		return Stream.of(Arguments.of("TMON_IT_ZABBIX60_URL", ZabbixVersion.V6_0),
				Arguments.of("TMON_IT_ZABBIX70_URL", ZabbixVersion.V7_0),
				Arguments.of("TMON_IT_ZABBIX80_URL", ZabbixVersion.V8_0));
	}

	private static final long G_PARENT = 990001, G_CHILD = 990002, G_OTHER = 990003, HOST = 990011, IFACE = 990021,
			HG1 = 990031, HG2 = 990032, ALERT = 990041, EVENT = 990051;

	@ParameterizedTest
	@MethodSource("servers")
	void readersWorkOnRealSchema(String envName, ZabbixVersion expected) {
		String url = System.getenv(envName);
		assumeTrue(url != null && !url.isBlank(), envName + " not set");
		String user = System.getenv().getOrDefault("TMON_IT_USER", "zabbix");
		String password = System.getenv().getOrDefault("TMON_IT_PASSWORD", "zabbix");

		ZabbixDatabases.withSingleConnection(url, user, password, db -> {
			var detected = ZabbixVersionDetector.detect(db);
			assertEquals(expected, detected.version().orElseThrow());
			ZabbixSchema schema = ZabbixSchema.of(expected);
			fixture(db, expected);
			try {
				check(db, schema);
			} finally {
				cleanup(db);
			}
			return null;
		});
	}

	private static void check(Database db, ZabbixSchema schema) {
		Page page = Page.first(100);

		// host groups: template groups excluded on 7.0, counts work
		var groups = new HostGroupReader(schema).search(db, AccessScope.ALL, null, Page.first(1000));
		assertTrue(groups.stream().anyMatch(g -> g.name().equals("Zabbix servers") && g.hostCount() >= 1));
		if (schema.version() != ZabbixVersion.V6_0) {
			assertTrue(groups.stream().noneMatch(g -> g.name().startsWith("Templates")), "template groups excluded");
		}
		assertEquals(Set.of(G_CHILD), new HostGroupReader(schema).search(db, AccessScope.of(Set.of(G_CHILD)), null, page)
				.stream().map(HostGroupReader.HostGroupSummary::groupId).collect(java.util.stream.Collectors.toSet()));
		assertEquals(G_CHILD, new HostGroupReader(schema).get(db, AccessScope.of(Set.of(G_CHILD)), G_CHILD).orElseThrow().groupId());
		assertTrue(new HostGroupReader(schema).get(db, AccessScope.of(Set.of(G_CHILD)), G_OTHER).isEmpty(), "out of scope");

		// hosts: all, by text / IP, scoped
		HostReader hosts = new HostReader(schema);
		var all = hosts.search(db, AccessScope.ALL, new HostReader.HostQuery(null, null, null, page));
		assertTrue(all.stream().anyMatch(h -> h.host().equals("Zabbix server")));
		assertTrue(all.stream().anyMatch(h -> h.host().equals("tmon-it-host") && h.groups().contains("tmon-it/child")
				&& h.interfaces().get(0).address().equals("192.0.2.10")));
		assertEquals(1, hosts.search(db, AccessScope.ALL, new HostReader.HostQuery("192.0.2.10", null, null, page)).size());
		assertEquals(List.of("tmon-it-host"), hosts.search(db, AccessScope.of(Set.of(G_CHILD)),
				new HostReader.HostQuery(null, null, null, page)).stream().map(HostReader.HostSummary::host).toList());
		assertTrue(hosts.search(db, AccessScope.of(Set.of(G_OTHER)), new HostReader.HostQuery(null, null, null, page)).isEmpty());
		assertTrue(hosts.search(db, AccessScope.NONE, new HostReader.HostQuery(null, null, null, page)).isEmpty());
		long zabbixServerId = all.stream().filter(h -> h.host().equals("Zabbix server")).findFirst().orElseThrow().hostId();
		var detail = hosts.get(db, AccessScope.ALL, zabbixServerId).orElseThrow();
		assertTrue(detail.itemCount() > 0 && detail.triggerCount() > 0);
		assertFalse(detail.templates().isEmpty());
		assertTrue(hosts.get(db, AccessScope.of(Set.of(G_CHILD)), zabbixServerId).isEmpty(), "out of scope");

		// subgroups by name
		User viewer = new User(1, "v", "v", null, "en", true, false, false, 0, null, null, Set.of(Role.VIEWER), null, null);
		AccessScope withSub = AccessResolver.resolve(viewer, new Assignments(Set.of(),
				List.of(new UserScope(1, G_PARENT, "tmon-it", true))), 1, db);
		AccessScope withoutSub = AccessResolver.resolve(viewer, new Assignments(Set.of(),
				List.of(new UserScope(1, G_PARENT, "tmon-it", false))), 1, db);
		assertEquals(1, hosts.search(db, withSub, new HostReader.HostQuery(null, null, null, page)).size());
		assertTrue(hosts.search(db, withoutSub, new HostReader.HostQuery(null, null, null, page)).isEmpty());

		// items with latest values (the Zabbix server collects its internal items)
		var items = new ItemReader(schema).search(db, AccessScope.ALL, new ItemReader.ItemQuery(zabbixServerId, null, null, page));
		assertFalse(items.isEmpty());
		assertTrue(new ItemReader(schema).search(db, AccessScope.of(Set.of(G_CHILD)),
				new ItemReader.ItemQuery(zabbixServerId, null, null, page)).isEmpty());
		new ItemReader(schema).search(db, AccessScope.ALL, new ItemReader.ItemQuery(null, "cpu", 0, Page.first(20)));

		// problems
		new ProblemReader().search(db, AccessScope.ALL, new ProblemReader.ProblemQuery(null, null, null, null, page));
		new ProblemReader().search(db, AccessScope.of(Set.of(G_CHILD)), new ProblemReader.ProblemQuery(2, "agent", zabbixServerId, false, page));

		// alerts: the fixture alert is visible to admins, not to a scope without its host
		Instant now = Instant.now();
		var q = new AlertReader.AlertQuery(now.minus(Duration.ofDays(1)), now.plusSeconds(60), null, null, null, page);
		assertTrue(new AlertReader().search(db, AccessScope.ALL, q).stream().anyMatch(a -> a.alertId() == ALERT));
		assertTrue(new AlertReader().search(db, AccessScope.of(Set.of(G_CHILD)), q).stream().noneMatch(a -> a.alertId() == ALERT));

		// dashboard aggregates (T-301)
		var dash = new org.tamacat.monitor.zabbix.reader.DashboardReader();
		var sumAll = dash.summary(db, AccessScope.ALL);
		assertTrue(sumAll.hostsMonitored() >= 2, "Zabbix server + fixture host");
		assertTrue(sumAll.itemsEnabled() > 0);
		var sumChild = dash.summary(db, AccessScope.of(Set.of(G_CHILD)));
		assertEquals(1, sumChild.hostsMonitored());
		assertEquals(0, sumChild.itemsEnabled());
		assertEquals(0, sumChild.problems());
		var sumNone = dash.summary(db, AccessScope.NONE);
		assertEquals(0, sumNone.hostsMonitored() + sumNone.problems() + sumNone.itemsEnabled());
		assertEquals(sumAll.toMap(),
				org.tamacat.monitor.zabbix.reader.DashboardReader.Summary.fromMap(sumAll.toMap()).toMap(), "map round trip");
		dash.unavailableHosts(db, AccessScope.ALL, page);

		// actions
		var actions = new ActionReader().search(db, null, null, page);
		assertTrue(actions.size() >= 5);
		var report = actions.stream().filter(a -> a.name().equals("Report problems to Zabbix administrators")).findFirst().orElseThrow();
		var ad = new ActionReader().get(db, report.actionId()).orElseThrow();
		assertFalse(ad.operations().isEmpty());
		assertTrue(ad.operations().stream().anyMatch(o -> o.userGroups().contains("Zabbix administrators")));
	}

	private static void fixture(Database db, ZabbixVersion v) {
		cleanup(db);
		String groupCols = v == ZabbixVersion.V6_0 ? "(groupid, name, internal, flags, uuid)" : "(groupid, name, flags, uuid, type)";
		String groupVals = v == ZabbixVersion.V6_0 ? "(?, ?, 0, 0, '')" : "(?, ?, 0, '', 0)";
		db.update(PreparedSql.of("insert into hstgrp " + groupCols + " values " + groupVals, G_PARENT, "tmon-it"));
		db.update(PreparedSql.of("insert into hstgrp " + groupCols + " values " + groupVals, G_CHILD, "tmon-it/child"));
		db.update(PreparedSql.of("insert into hstgrp " + groupCols + " values " + groupVals, G_OTHER, "tmon-it-other"));
		// 8.0: hosts.readme is TEXT without a default
		String readme = v == ZabbixVersion.V8_0 ? ", readme" : "";
		String readmeVal = v == ZabbixVersion.V8_0 ? ", ''" : "";
		db.update(PreparedSql.of("insert into hosts (hostid, host, name, status, flags, description, uuid" + readme + ") "
				+ "values (?, 'tmon-it-host', 'tmon-it-host', 0, 0, '', ''" + readmeVal + ")", HOST));
		db.update(PreparedSql.of("insert into hosts_groups (hostgroupid, hostid, groupid) values (?, ?, ?)", HG1, HOST, G_CHILD));
		db.update(PreparedSql.of("insert into interface (interfaceid, hostid, main, type, useip, ip, dns, port) "
				+ "values (?, ?, 1, 1, 1, '192.0.2.10', '', '10050')", IFACE, HOST));
		// an event of a trigger on the "Zabbix server" host, and an alert for it
		long triggerId = db.queryOne(PreparedSql.of("select min(f.triggerid) from functions f join items i on i.itemid = f.itemid "
				+ "join hosts h on h.hostid = i.hostid where h.host = 'Zabbix server'"), r -> r.getLong(1)).orElseThrow();
		int now = (int) Instant.now().getEpochSecond();
		db.update(PreparedSql.of("insert into events (eventid, source, object, objectid, clock, value, acknowledged, ns, name, severity) "
				+ "values (?, 0, 0, ?, ?, 1, 0, 0, 'tmon-it event', 3)", EVENT, triggerId, now));
		db.update(PreparedSql.of("insert into alerts (alertid, actionid, eventid, clock, sendto, subject, message, status, "
				+ "retries, error, esc_step, alerttype, parameters) values (?, 3, ?, ?, 'ops@example.com', 'it', 'it', 1, 0, '', 1, 0, '{}')",
				ALERT, EVENT, now));
		// keep HG2 id reserved for future fixtures
		assertTrue(HG2 > HG1);
	}

	private static void cleanup(Database db) {
		db.update(PreparedSql.of("delete from alerts where alertid = ?", ALERT));
		db.update(PreparedSql.of("delete from events where eventid = ?", EVENT));
		db.update(PreparedSql.of("delete from interface where interfaceid = ?", IFACE));
		db.update(PreparedSql.of("delete from hosts_groups where hostgroupid in (?, ?)", HG1, HG2));
		db.update(PreparedSql.of("delete from hosts where hostid = ?", HOST));
		db.update(PreparedSql.of("delete from hstgrp where groupid in (?, ?, ?)", G_PARENT, G_CHILD, G_OTHER));
	}
}
