package org.tamacat.monitor.zabbix.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.json.Json;

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
 * Golden-master tests of template link / unlink / unlink and clear against the Zabbix API (T-601). For
 * each case two identical hosts are used: one changed through the API, one by {@link TemplateLinker}.
 */
class TemplateGoldenMasterTest {

	static Stream<Arguments> servers() {
		return Stream.of(Arguments.of("60", ZabbixVersion.V6_0), Arguments.of("70", ZabbixVersion.V7_0),
				Arguments.of("80", ZabbixVersion.V8_0));
	}

	private static <R> R write(Database db, ZabbixVersion v, Function<WriteContext, R> work) {
		return db.inTransaction(tx -> work.apply(new WriteContext(tx, null, ZabbixSchema.of(v), new IdAllocator())));
	}

	@ParameterizedTest
	@MethodSource("servers")
	void templateLinksEqualTheZabbixApi(String v, ZabbixVersion version) {
		String url = System.getenv("TMON_IT_ZABBIX" + v + "_URL");
		String apiUrl = System.getenv("TMON_IT_ZABBIX" + v + "_API");
		assumeTrue(url != null && apiUrl != null, "TMON_IT_ZABBIX" + v + "_URL / _API not set");
		ZabbixApiClient api = new ZabbixApiClient(apiUrl, version != ZabbixVersion.V6_0).login("Admin", "zabbix");
		String sfx = UUID.randomUUID().toString().substring(0, 6);

		ZabbixDatabases.withSingleConnection(url, "zabbix", "zabbix", db -> {
			GoldenMaster gm = new GoldenMaster(db);
			TemplateLinker linker = new TemplateLinker();
			List<Long> hosts = new ArrayList<>();
			long tpl = template(api, db, "gm-tpl-" + sfx);
			try {
				long h1 = host(api, db, "gm-tl1-" + sfx, hosts);
				long h2 = host(api, db, "gm-tl2-" + sfx, hosts);
				var linkApi = gm.capture(() -> linkByApi(api, h1, tpl));
				var linkWriter = gm.capture(() -> write(db, version, ctx -> {
					linker.link(ctx, h2, "gm-tl2-" + sfx, tpl);
					return null;
				}));
				same(linkApi, linkWriter, "link");

				var unlinkApi = gm.capture(() -> api.call("host.update", Json.createObjectBuilder().add("hostid", String.valueOf(h1))
						.add("templates", Json.createArrayBuilder()).build()));
				var unlinkWriter = gm.capture(() -> write(db, version, ctx -> {
					linker.unlink(ctx, h2, "gm-tl2-" + sfx, tpl, false);
					return null;
				}));
				same(unlinkApi, unlinkWriter, "unlink");

				// the kept copies now conflict with the template's keys
				ConflictException conflict = assertThrows(ConflictException.class, () -> write(db, version, ctx -> {
					linker.link(ctx, h2, "gm-tl2-" + sfx, tpl);
					return null;
				}));
				assertEquals("error.template.itemConflict", conflict.messageKey());

				long h3 = host(api, db, "gm-tl3-" + sfx, hosts);
				long h4 = host(api, db, "gm-tl4-" + sfx, hosts);
				linkByApi(api, h3, tpl);
				write(db, version, ctx -> {
					linker.link(ctx, h4, "gm-tl4-" + sfx, tpl);
					return null;
				});
				ConflictException twice = assertThrows(ConflictException.class, () -> write(db, version, ctx -> {
					linker.link(ctx, h4, "gm-tl4-" + sfx, tpl);
					return null;
				}));
				assertEquals("error.template.alreadyLinked", twice.messageKey());
				var clearApi = gm.capture(() -> api.call("host.update", Json.createObjectBuilder().add("hostid", String.valueOf(h3))
						.add("templates_clear", Json.createArrayBuilder().add(Json.createObjectBuilder().add("templateid", String.valueOf(tpl))))
						.build()));
				var clearWriter = gm.capture(() -> write(db, version, ctx -> {
					linker.unlink(ctx, h4, "gm-tl4-" + sfx, tpl, true);
					return null;
				}));
				same(clearApi, clearWriter, "unlink and clear");

				// templates with discovery rules are refused
				long linuxTpl = db.queryOne(PreparedSql.of("select min(h.hostid) from hosts h where h.status = 3 and exists "
						+ "(select 1 from items i where i.hostid = h.hostid and i.flags = 1)"), r -> r.getLong(1)).orElseThrow();
				assertEquals("error.template.discovery", TemplateLinker.unsupported(db, linuxTpl).orElseThrow());
			} finally {
				for (long h : hosts) {
					api.call("host.delete", Json.createArrayBuilder().add(String.valueOf(h)).build());
				}
				api.call("template.delete", Json.createArrayBuilder().add(String.valueOf(tpl)).build());
			}
			return null;
		});
	}

	private static void linkByApi(ZabbixApiClient api, long hostId, long templateId) {
		api.call("host.update", Json.createObjectBuilder().add("hostid", String.valueOf(hostId))
				.add("templates", Json.createArrayBuilder().add(Json.createObjectBuilder().add("templateid", String.valueOf(templateId))))
				.build());
	}

	/** Agent item (tag, preprocessing), active agent item, trapper; triggers with tags and a dependency; graphs. */
	private static long template(ZabbixApiClient api, SqlOperations db, String name) {
		long group = db.queryOne(PreparedSql.of("select groupid from hstgrp where name = 'Templates'"), r -> r.getLong(1)).orElseThrow();
		long tpl = ZabbixApiClient.firstId(api.call("template.create", Json.createObjectBuilder().add("host", name)
				.add("groups", Json.createArrayBuilder().add(Json.createObjectBuilder().add("groupid", String.valueOf(group))))
				.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "class").add("value", "os")))
				.build()), "templateids");
		long a = ZabbixApiClient.firstId(api.call("item.create", Json.createObjectBuilder().add("hostid", String.valueOf(tpl))
				.add("name", "CPU load").add("key_", "system.cpu.load").add("type", 0).add("value_type", 0).add("delay", "1m")
				.add("description", "d")
				.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "component").add("value", "cpu")))
				.add("preprocessing", Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", 1).add("params", "2")
						.add("error_handler", 0).add("error_handler_params", "")))
				.build()), "itemids");
		long u = ZabbixApiClient.firstId(api.call("item.create", Json.createObjectBuilder().add("hostid", String.valueOf(tpl))
				.add("name", "Uptime").add("key_", "system.uptime").add("type", 7).add("value_type", 3).add("delay", "5m")
				.add("units", "uptime").build()), "itemids");
		api.call("item.create", Json.createObjectBuilder().add("hostid", String.valueOf(tpl)).add("name", "Trap").add("key_", "app.trap")
				.add("type", 2).add("value_type", 3).build());
		long t1 = ZabbixApiClient.firstId(api.call("trigger.create", Json.createObjectBuilder().add("description", "High load")
				.add("expression", "last(/" + name + "/system.cpu.load)>5").add("priority", 3)
				.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "scope").add("value", "perf")))
				.build()), "triggerids");
		api.call("trigger.create", Json.createObjectBuilder().add("description", "Trap and load")
				.add("expression", "last(/" + name + "/app.trap)>0 and last(/" + name + "/system.cpu.load)>1").add("priority", 2)
				.add("recovery_mode", 1).add("recovery_expression", "last(/" + name + "/app.trap)=0")
				.add("dependencies", Json.createArrayBuilder().add(Json.createObjectBuilder().add("triggerid", String.valueOf(t1))))
				.build());
		api.call("graph.create", Json.createObjectBuilder().add("name", "Load").add("width", 900).add("height", 200)
				.add("gitems", Json.createArrayBuilder().add(Json.createObjectBuilder().add("itemid", String.valueOf(a)).add("color", "1A7C11")))
				.build());
		api.call("graph.create", Json.createObjectBuilder().add("name", "Load and uptime").add("width", 900).add("height", 200)
				.add("ymax_type", 2).add("ymax_itemid", String.valueOf(a))
				.add("gitems", Json.createArrayBuilder().add(Json.createObjectBuilder().add("itemid", String.valueOf(a)).add("color", "1A7C11"))
						.add(Json.createObjectBuilder().add("itemid", String.valueOf(u)).add("color", "F63100")))
				.build());
		return tpl;
	}

	private static long host(ZabbixApiClient api, SqlOperations db, String name, List<Long> created) {
		long linux = db.queryOne(PreparedSql.of("select groupid from hstgrp where name = 'Linux servers'"), r -> r.getLong(1)).orElseThrow();
		long id = ZabbixApiClient.firstId(api.call("host.create", Json.createObjectBuilder().add("host", name)
				.add("groups", Json.createArrayBuilder().add(Json.createObjectBuilder().add("groupid", String.valueOf(linux))))
				.add("interfaces", Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", 1).add("main", 1)
						.add("useip", 1).add("ip", "192.0.2.88").add("dns", "").add("port", "10050")))
				.build()), "hostids");
		created.add(id);
		return id;
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
}
