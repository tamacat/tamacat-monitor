package org.tamacat.monitor.zabbix.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Database;
import org.tamacat.sql.SqlOperations;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.zabbix.ZabbixDatabases;
import org.tamacat.monitor.zabbix.ZabbixSchema;
import org.tamacat.monitor.zabbix.ZabbixVersion;

/**
 * Golden-master tests of deletes against the Zabbix API (T-408). The same host (items, a dependent item,
 * triggers with a dependency, graphs incl. a y axis item) is built through the API twice; once the API
 * deletes an item and then the host, once the writers do. Housekeeper rows are compared separately (the
 * table is excluded from {@link GoldenMaster} because the Zabbix server consumes it).
 */
class DeleteGoldenMasterTest {

	static Stream<Arguments> servers() {
		return Stream.of(Arguments.of("60", ZabbixVersion.V6_0), Arguments.of("70", ZabbixVersion.V7_0), Arguments.of("80", ZabbixVersion.V8_0));
	}

	private static <R> R write(Database db, ZabbixVersion v, Function<WriteContext, R> work) {
		return db.inTransaction(tx -> work.apply(new WriteContext(tx, null, ZabbixSchema.of(v), new IdAllocator())));
	}

	record Setup(long hostId, long itemA, long itemB, long trigger1) {
	}

	@ParameterizedTest
	@MethodSource("servers")
	void deletesEqualTheZabbixApi(String v, ZabbixVersion version) {
		String url = System.getenv("TMON_IT_ZABBIX" + v + "_URL");
		String apiUrl = System.getenv("TMON_IT_ZABBIX" + v + "_API");
		assumeTrue(url != null && apiUrl != null, "TMON_IT_ZABBIX" + v + "_URL / _API not set");
		ZabbixApiClient api = new ZabbixApiClient(apiUrl, version != ZabbixVersion.V6_0).login("Admin", "zabbix");
		String host = "gm-del-" + UUID.randomUUID().toString().substring(0, 6);

		ZabbixDatabases.withSingleConnection(url, "zabbix", "zabbix", db -> {
			GoldenMaster gm = new GoldenMaster(db);
			Long[] cleanup = { null };
			try {
				// --- by the API ---
				Setup a = setup(api, db, host);
				cleanup[0] = a.hostId();
				long hk = maxHousekeeper(db);
				var itemApi = gm.capture(() -> api.call("item.delete", Json.createArrayBuilder().add(String.valueOf(a.itemA())).build()));
				var itemApiHk = housekeeper(db, version, hk);
				hk = maxHousekeeper(db);
				var hostApi = gm.capture(() -> api.call("host.delete", Json.createArrayBuilder().add(String.valueOf(a.hostId())).build()));
				var hostApiHk = housekeeper(db, version, hk);
				cleanup[0] = null;

				// --- by the writers ---
				Setup w = setup(api, db, host);
				cleanup[0] = w.hostId();
				ItemWriter items = new ItemWriter();
				HostWriter hosts = new HostWriter();
				// refusals first: the dependent item, a trigger used by an action
				ConflictException dep = assertThrows(ConflictException.class, () -> write(db, version, ctx -> {
					items.delete(ctx, w.itemB(), "gm.b");
					return null;
				}));
				assertEquals("error.delete.dependentItems", dep.messageKey());
				long actionId = ZabbixApiClient.firstId(api.call("action.create", Json.createObjectBuilder().add("name", host + "-act")
						.add("eventsource", 0).add("filter", Json.createObjectBuilder().add("evaltype", 0).add("conditions",
								Json.createArrayBuilder().add(Json.createObjectBuilder().add("conditiontype", 2).add("operator", 0)
										.add("value", String.valueOf(w.trigger1())))))
						.add("operations", Json.createArrayBuilder().add(Json.createObjectBuilder().add("operationtype", 0)
								.add("opmessage", Json.createObjectBuilder().add("default_msg", 1))
								.add("opmessage_usr", Json.createArrayBuilder().add(Json.createObjectBuilder().add("userid", "1")))))
						.build()), "actionids");
				try {
					ConflictException used = assertThrows(ConflictException.class, () -> write(db, version, ctx -> {
						items.delete(ctx, w.itemA(), "gm.a");
						return null;
					}));
					assertEquals("error.delete.triggerInAction", used.messageKey());
				} finally {
					api.call("action.delete", Json.createArrayBuilder().add(String.valueOf(actionId)).build());
				}
				ConflictException lld = assertThrows(ConflictException.class, () -> write(db, version, ctx -> {
					long zbx = ctx.db().queryOne(PreparedSql.of("select hostid from hosts where host = 'Zabbix server'"), r -> r.getLong(1))
							.orElseThrow();
					hosts.delete(ctx, zbx, "Zabbix server");
					return null;
				}));
				assertEquals("error.host.hasDiscovery", lld.messageKey());

				hk = maxHousekeeper(db);
				var itemWriter = gm.capture(() -> write(db, version, ctx -> {
					items.delete(ctx, w.itemA(), "gm.a");
					return null;
				}));
				var itemWriterHk = housekeeper(db, version, hk);
				same(itemApi, itemWriter, "item delete");
				assertEquals(itemApiHk, itemWriterHk, "item delete: housekeeper");

				hk = maxHousekeeper(db);
				var hostWriter = gm.capture(() -> write(db, version, ctx -> {
					hosts.delete(ctx, w.hostId(), host);
					return null;
				}));
				var hostWriterHk = housekeeper(db, version, hk);
				cleanup[0] = null;
				same(hostApi, hostWriter, "host delete");
				assertEquals(hostApiHk, hostWriterHk, "host delete: housekeeper");

				// --- action delete ---
				ActionWriter actions = new ActionWriter();
				long admins = db.queryOne(PreparedSql.of("select usrgrpid from usrgrp where name = 'Zabbix administrators'"),
						r -> r.getLong(1)).orElseThrow();
				var def = new ActionWriter.ActionInput(host + "-a", 0, "1h", true, true, true, 0,
						List.of(new ActionWriter.Condition(4, 5, "3", "")),
						List.of(new ActionWriter.Operation(0, 1, 2, "0",
								new ActionWriter.Message(false, "S", "M", null, List.of(admins), List.of(1L)))),
						List.of(new ActionWriter.Operation(11, 1, 1, "0", new ActionWriter.Message(true, null, null, null, List.of(), List.of()))),
						List.of());
				long id1 = ZabbixApiClient.firstId(api.call("action.create", ActionGoldenMasterTest.json(def, null, version)), "actionids");
				var actApi = gm.capture(() -> api.call("action.delete", Json.createArrayBuilder().add(String.valueOf(id1)).build()));
				long id2 = ZabbixApiClient.firstId(api.call("action.create", ActionGoldenMasterTest.json(def, null, version)), "actionids");
				var actWriter = gm.capture(() -> write(db, version, ctx -> {
					actions.delete(ctx, id2, host + "-a");
					return null;
				}));
				same(actApi, actWriter, "action delete");
			} finally {
				if (cleanup[0] != null) {
					api.call("host.delete", Json.createArrayBuilder().add(String.valueOf(cleanup[0])).build());
				}
			}
			return null;
		});
	}

	/** Equal, or fails listing only the rows that differ per table. */
	private static void same(Map<String, List<String>> api, Map<String, List<String>> writer, String what) {
		if (api.equals(writer)) {
			return;
		}
		StringBuilder sb = new StringBuilder(what + " differs:");
		java.util.Set<String> tables = new java.util.TreeSet<>(api.keySet());
		tables.addAll(writer.keySet());
		for (String t : tables) {
			List<String> a = new ArrayList<>(api.getOrDefault(t, List.of()));
			List<String> w = new ArrayList<>(writer.getOrDefault(t, List.of()));
			List<String> onlyA = new ArrayList<>(a);
			w.forEach(onlyA::remove);
			List<String> onlyW = new ArrayList<>(w);
			a.forEach(onlyW::remove);
			onlyA.forEach(x -> sb.append(System.lineSeparator()).append("  api    ").append(t).append(": ").append(x));
			onlyW.forEach(x -> sb.append(System.lineSeparator()).append("  writer ").append(t).append(": ").append(x));
		}
		org.junit.jupiter.api.Assertions.fail(sb.toString());
	}

	/** Host with items a, b, c (dependent on b), triggers 1 (a), 2 (a and b), 3 (b, depends on 1), graphs. */
	private static Setup setup(ZabbixApiClient api, SqlOperations db, String host) {
		long linux = db.queryOne(PreparedSql.of("select groupid from hstgrp where name = 'Linux servers'"), r -> r.getLong(1)).orElseThrow();
		long hostId = ZabbixApiClient.firstId(api.call("host.create", Json.createObjectBuilder().add("host", host)
				.add("groups", Json.createArrayBuilder().add(Json.createObjectBuilder().add("groupid", String.valueOf(linux))))
				.add("interfaces", Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", 1).add("main", 1)
						.add("useip", 1).add("ip", "192.0.2.97").add("dns", "").add("port", "10050")))
				.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "env").add("value", "gm")))
				.build()), "hostids");
		long ifid = db.queryOne(PreparedSql.of("select interfaceid from interface where hostid = ?", hostId), r -> r.getLong(1)).orElseThrow();
		long a = item(api, Json.createObjectBuilder().add("hostid", String.valueOf(hostId)).add("name", "A").add("key_", "gm.a")
				.add("type", 0).add("value_type", 0).add("interfaceid", String.valueOf(ifid)).add("delay", "1m")
				.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "c").add("value", "a"))).build());
		long b = item(api, Json.createObjectBuilder().add("hostid", String.valueOf(hostId)).add("name", "B").add("key_", "gm.b")
				.add("type", 0).add("value_type", 3).add("interfaceid", String.valueOf(ifid)).add("delay", "1m").build());
		item(api, Json.createObjectBuilder().add("hostid", String.valueOf(hostId)).add("name", "C").add("key_", "gm.c")
				.add("type", 18).add("value_type", 3).add("master_itemid", String.valueOf(b)).build());
		long t1 = trigger(api, host, "T1", "last(/" + host + "/gm.a)>1", null);
		trigger(api, host, "T2", "last(/" + host + "/gm.a)>1 and last(/" + host + "/gm.b)>1", null);
		trigger(api, host, "T3", "last(/" + host + "/gm.b)>5", t1);
		graph(api, "G1", List.of(a), null);
		graph(api, "G2", List.of(a, b), null);
		graph(api, "G3", List.of(b), a);
		return new Setup(hostId, a, b, t1);
	}

	private static long item(ZabbixApiClient api, JsonObject o) {
		return ZabbixApiClient.firstId(api.call("item.create", o), "itemids");
	}

	private static long trigger(ZabbixApiClient api, String host, String name, String expression, Long dependsOn) {
		var b = Json.createObjectBuilder().add("description", name).add("expression", expression).add("priority", 3)
				.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "t").add("value", name)));
		if (dependsOn != null) {
			b.add("dependencies", Json.createArrayBuilder().add(Json.createObjectBuilder().add("triggerid", String.valueOf(dependsOn))));
		}
		return ZabbixApiClient.firstId(api.call("trigger.create", b.build()), "triggerids");
	}

	private static void graph(ZabbixApiClient api, String name, List<Long> items, Long ymaxItem) {
		var gitems = Json.createArrayBuilder();
		String[] colors = { "1A7C11", "F63100" };
		for (int i = 0; i < items.size(); i++) {
			gitems.add(Json.createObjectBuilder().add("itemid", String.valueOf(items.get(i))).add("color", colors[i % 2]));
		}
		var b = Json.createObjectBuilder().add("name", name).add("width", 900).add("height", 200).add("gitems", gitems);
		if (ymaxItem != null) {
			b.add("ymax_type", 2).add("ymax_itemid", String.valueOf(ymaxItem));
		}
		api.call("graph.create", b.build());
	}

	private static long maxHousekeeper(SqlOperations db) {
		return db.queryOne(PreparedSql.of("select coalesce(max(housekeeperid), 0) from housekeeper"), r -> r.getLong(1)).orElse(0L);
	}

	/** New housekeeper rows: "table/field" -> number of rows, plus the number of distinct values per field. */
	private static Map<String, Integer> housekeeper(SqlOperations db, ZabbixVersion version, long after) {
		Map<String, Integer> out = new TreeMap<>();
		// 8.0: (object, objectid) written by DB triggers; object 0 = item ("itemid"), 1 = trigger ("triggerid")
		String select = version == ZabbixVersion.V8_0
				? "select 'object' as tablename, case object when 1 then 'triggerid' else 'itemid' end as field, objectid as value, "
						+ "object from housekeeper where housekeeperid > ?"
				: "select tablename, field, value, 0 from housekeeper where housekeeperid > ?";
		List<String[]> rows = new ArrayList<>(db.queryList(PreparedSql.of(select, after),
				r -> new String[] { r.getString(1) + (version == ZabbixVersion.V8_0 ? r.getString(4) : ""), r.getString(2),
						r.getString(3) }));
		for (String[] r : rows) {
			out.merge(r[0] + "/" + r[1], 1, Integer::sum);
		}
		out.put("distinct itemid", (int) rows.stream().filter(r -> r[1].equals("itemid")).map(r -> r[2]).distinct().count());
		out.put("distinct triggerid", (int) rows.stream().filter(r -> r[1].equals("triggerid")).map(r -> r[2]).distinct().count());
		return out;
	}
}
