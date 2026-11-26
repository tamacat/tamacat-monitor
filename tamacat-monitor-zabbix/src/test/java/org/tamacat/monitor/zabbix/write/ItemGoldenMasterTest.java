package org.tamacat.monitor.zabbix.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
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
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.zabbix.ZabbixDatabases;
import org.tamacat.monitor.zabbix.ZabbixSchema;
import org.tamacat.monitor.zabbix.ZabbixVersion;
import org.tamacat.monitor.zabbix.write.HostWriter.Tag;
import org.tamacat.monitor.zabbix.write.ItemWriter.ItemInput;
import org.tamacat.monitor.zabbix.write.ItemWriter.ItemUpdate;

/** Golden-master tests of item writes against the Zabbix API (T-406). */
class ItemGoldenMasterTest {

	static Stream<Arguments> servers() {
		return Stream.of(Arguments.of("60", ZabbixVersion.V6_0), Arguments.of("70", ZabbixVersion.V7_0), Arguments.of("80", ZabbixVersion.V8_0));
	}

	private static <R> R write(Database db, ZabbixVersion v, Function<WriteContext, R> work) {
		return db.inTransaction(tx -> work.apply(new WriteContext(tx, null, ZabbixSchema.of(v), new IdAllocator())));
	}

	@ParameterizedTest
	@MethodSource("servers")
	void itemWritesEqualTheZabbixApi(String v, ZabbixVersion version) {
		String url = System.getenv("TMON_IT_ZABBIX" + v + "_URL");
		String apiUrl = System.getenv("TMON_IT_ZABBIX" + v + "_API");
		assumeTrue(url != null && apiUrl != null, "TMON_IT_ZABBIX" + v + "_URL / _API not set");
		ZabbixApiClient api = new ZabbixApiClient(apiUrl, version != ZabbixVersion.V6_0).login("Admin", "zabbix");
		String suffix = UUID.randomUUID().toString().substring(0, 6);

		ZabbixDatabases.withSingleConnection(url, "zabbix", "zabbix", db -> {
			long linux = db.queryOne(PreparedSql.of("select groupid from hstgrp where name = 'Linux servers'"), r -> r.getLong(1)).orElseThrow();
			long hostId = ZabbixApiClient.firstId(api.call("host.create", Json.createObjectBuilder().add("host", "gm-item-" + suffix)
					.add("groups", Json.createArrayBuilder().add(Json.createObjectBuilder().add("groupid", String.valueOf(linux))))
					.add("interfaces", Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", 1).add("main", 1)
							.add("useip", 1).add("ip", "192.0.2.95").add("dns", "").add("port", "10050")))
					.build()), "hostids");
			long ifid = db.queryOne(PreparedSql.of("select interfaceid from interface where hostid = ?", hostId), r -> r.getLong(1)).orElseThrow();
			try {
				GoldenMaster gm = new GoldenMaster(db);
				ItemWriter writer = new ItemWriter();
				long[] id = new long[1];

				// agent item with tags
				JsonObject agent = Json.createObjectBuilder().add("hostid", String.valueOf(hostId)).add("name", "CPU load")
						.add("key_", "system.cpu.load[all,avg1]").add("type", 0).add("value_type", 0).add("interfaceid", String.valueOf(ifid))
						.add("delay", "1m").add("history", "31d").add("trends", "365d").add("units", "").add("description", "gm")
						.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "component").add("value", "cpu")))
						.build();
				var byApi = gm.capture(() -> id[0] = ZabbixApiClient.firstId(api.call("item.create", agent), "itemids"));
				api.call("item.delete", Json.createArrayBuilder().add(String.valueOf(id[0])).build());
				var byWriter = gm.capture(() -> id[0] = write(db, version, ctx -> writer.create(ctx, new ItemInput(hostId, "CPU load",
						"system.cpu.load[all,avg1]", 0, 0, "1m", "31d", "365d", "", "gm", 0, List.of(new Tag("component", "cpu"))))));
				assertEquals(byApi, byWriter, "agent item");

				// update name, delay, status, tags
				byApi = gm.capture(() -> api.call("item.update", Json.createObjectBuilder().add("itemid", String.valueOf(id[0]))
						.add("name", "CPU load 1m").add("delay", "30s").add("status", 1)
						.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "component").add("value", "system")))
						.build()));
				api.call("item.update", Json.createObjectBuilder().add("itemid", String.valueOf(id[0])).add("name", "CPU load")
						.add("delay", "1m").add("status", 0)
						.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "component").add("value", "cpu")))
						.build());
				byWriter = gm.capture(() -> write(db, version, ctx -> {
					writer.update(ctx, id[0], new ItemUpdate("system.cpu.load[all,avg1]", "CPU load", "CPU load 1m", "30s", null, null,
							null, null, 1, List.of(new Tag("component", "system"))));
					return null;
				}));
				assertEquals(byApi, byWriter, "item update");

				// trapper (text) and active agent
				var trapApi = gm.capture(() -> id[0] = ZabbixApiClient.firstId(api.call("item.create", Json.createObjectBuilder()
						.add("hostid", String.valueOf(hostId)).add("name", "Trap").add("key_", "app.trap").add("type", 2).add("value_type", 4)
						.add("history", "7d").build()), "itemids"));
				api.call("item.delete", Json.createArrayBuilder().add(String.valueOf(id[0])).build());
				var trapWriter = gm.capture(() -> write(db, version, ctx -> writer.create(ctx, new ItemInput(hostId, "Trap", "app.trap", 2, 4,
						null, "7d", null, null, null, 0, null))));
				assertEquals(trapApi, trapWriter, "trapper item");

				var activeApi = gm.capture(() -> id[0] = ZabbixApiClient.firstId(api.call("item.create", Json.createObjectBuilder()
						.add("hostid", String.valueOf(hostId)).add("name", "Uptime").add("key_", "system.uptime").add("type", 7)
						.add("value_type", 3).add("delay", "5m").add("history", "31d").add("trends", "365d").add("units", "uptime").build()),
						"itemids"));
				api.call("item.delete", Json.createArrayBuilder().add(String.valueOf(id[0])).build());
				var activeWriter = gm.capture(() -> write(db, version, ctx -> writer.create(ctx, new ItemInput(hostId, "Uptime",
						"system.uptime", 7, 3, "5m", "31d", "365d", "uptime", null, 0, null))));
				assertEquals(activeApi, activeWriter, "active agent item");

				// refusals
				assertThrows(ConflictException.class, () -> write(db, version, ctx -> writer.create(ctx, new ItemInput(hostId, "dup",
						"app.trap", 2, 4, null, "7d", null, null, null, 0, null))));
				long templated = db.queryOne(PreparedSql.of("select min(i.itemid) from items i join hosts h on h.hostid = i.hostid "
						+ "where h.host = 'Zabbix server' and i.templateid is not null and i.flags = 0"), r -> r.getLong(1)).orElseThrow();
				var t = db.queryOne(PreparedSql.of("select key_, name from items where itemid = ?", templated),
						r -> new String[] { r.getString(1), r.getString(2) }).orElseThrow();
				ConflictException e = assertThrows(ConflictException.class, () -> write(db, version, ctx -> {
					writer.update(ctx, templated, new ItemUpdate(t[0], t[1], "x", null, null, null, null, null, null, null));
					return null;
				}));
				assertEquals("error.item.templated", e.messageKey());
				assertThrows(ValidationException.class, () -> write(db, version, ctx -> writer.create(ctx, new ItemInput(hostId, "bad",
						"bad key[", 2, 4, null, "7d", null, null, null, 0, null))));
				assertThrows(ValidationException.class, () -> write(db, version, ctx -> writer.create(ctx, new ItemInput(hostId, "bad",
						"agent.ping", 0, 3, "0", "7d", "365d", null, null, 0, null))));
			} finally {
				api.call("host.delete", Json.createArrayBuilder().add(String.valueOf(hostId)).build());
			}
			return null;
		});
	}
}
