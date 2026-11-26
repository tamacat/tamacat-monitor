package org.tamacat.monitor.zabbix.write;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.UUID;

import jakarta.json.Json;

import org.junit.jupiter.api.Test;
import org.tamacat.dao.PreparedSql;
import org.tamacat.monitor.zabbix.ZabbixDatabases;

/**
 * Exploration aid, not a regression test: prints what Zabbix API calls change in the DB.
 * Runs only with TMON_IT_OBSERVE=60 or 70 (and the URL / API variables of that version).
 */
class ObserveApiTest {

	@Test
	void observeHostWrites() {
		String v = System.getenv("TMON_IT_OBSERVE");
		assumeTrue(v != null, "TMON_IT_OBSERVE not set");
		String url = System.getenv("TMON_IT_ZABBIX" + v + "_URL");
		ZabbixApiClient api = new ZabbixApiClient(System.getenv("TMON_IT_ZABBIX" + v + "_API"), !v.equals("60"))
				.login("Admin", "zabbix");
		ZabbixDatabases.withSingleConnection(url, "zabbix", "zabbix", db -> {
			GoldenMaster gm = new GoldenMaster(db);
			long linux = db.queryOne(PreparedSql.of("select groupid from hstgrp where name = 'Linux servers'"), r -> r.getLong(1)).orElseThrow();
			long zbx = db.queryOne(PreparedSql.of("select groupid from hstgrp where name = 'Zabbix servers'"), r -> r.getLong(1)).orElseThrow();
			String name = "obs-" + UUID.randomUUID().toString().substring(0, 6);
			long[] id = new long[1];
			var create = gm.capture(() -> id[0] = ZabbixApiClient.firstId(api.call("host.create", Json.createObjectBuilder()
					.add("host", name).add("name", name + " visible").add("description", "d")
					.add("groups", Json.createArrayBuilder().add(Json.createObjectBuilder().add("groupid", String.valueOf(linux))))
					.add("interfaces", Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", 1).add("main", 1)
							.add("useip", 1).add("ip", "192.0.2.50").add("dns", "").add("port", "10050")))
					.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "env").add("value", "test")))
					.build()), "hostids"));
			print("create", create);
			var update = gm.capture(() -> api.call("host.update", Json.createObjectBuilder().add("hostid", String.valueOf(id[0]))
					.add("name", name + " renamed").add("status", 1)
					.add("groups", Json.createArrayBuilder().add(Json.createObjectBuilder().add("groupid", String.valueOf(linux)))
							.add(Json.createObjectBuilder().add("groupid", String.valueOf(zbx))))
					.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "env").add("value", "prod")))
					.build()));
			print("update groups+tags+status", update);
			var iface = gm.capture(() -> {
				long ifid = db.queryOne(PreparedSql.of("select interfaceid from interface where hostid = ?", id[0]), r -> r.getLong(1)).orElseThrow();
				api.call("hostinterface.update", Json.createObjectBuilder().add("interfaceid", String.valueOf(ifid))
						.add("ip", "192.0.2.51").add("port", "10051").build());
			});
			print("interface update", iface);
			var delete = gm.capture(() -> api.call("host.delete", Json.createArrayBuilder().add(String.valueOf(id[0])).build()));
			print("delete", delete);
			return null;
		});
	}

	@Test
	void observeItemWrites() {
		String v = System.getenv("TMON_IT_OBSERVE");
		assumeTrue(v != null, "TMON_IT_OBSERVE not set");
		String url = System.getenv("TMON_IT_ZABBIX" + v + "_URL");
		ZabbixApiClient api = new ZabbixApiClient(System.getenv("TMON_IT_ZABBIX" + v + "_API"), !v.equals("60"))
				.login("Admin", "zabbix");
		ZabbixDatabases.withSingleConnection(url, "zabbix", "zabbix", db -> {
			GoldenMaster gm = new GoldenMaster(db);
			long linux = db.queryOne(PreparedSql.of("select groupid from hstgrp where name = 'Linux servers'"), r -> r.getLong(1)).orElseThrow();
			String name = "obs-item-" + UUID.randomUUID().toString().substring(0, 6);
			long hostId = ZabbixApiClient.firstId(api.call("host.create", Json.createObjectBuilder().add("host", name)
					.add("groups", Json.createArrayBuilder().add(Json.createObjectBuilder().add("groupid", String.valueOf(linux))))
					.add("interfaces", Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", 1).add("main", 1)
							.add("useip", 1).add("ip", "192.0.2.90").add("dns", "").add("port", "10050")))
					.build()), "hostids");
			long ifid = db.queryOne(PreparedSql.of("select interfaceid from interface where hostid = ?", hostId), r -> r.getLong(1)).orElseThrow();
			try {
				long[] id = new long[1];
				var create = gm.capture(() -> id[0] = ZabbixApiClient.firstId(api.call("item.create", Json.createObjectBuilder()
						.add("hostid", String.valueOf(hostId)).add("name", "CPU load").add("key_", "system.cpu.load[all,avg1]")
						.add("type", 0).add("value_type", 0).add("interfaceid", String.valueOf(ifid)).add("delay", "1m")
						.add("history", "31d").add("trends", "365d").add("units", "").add("description", "observe")
						.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "component").add("value", "cpu")))
						.build()), "itemids"));
				print("item create", create);
				var trapper = gm.capture(() -> api.call("item.create", Json.createObjectBuilder()
						.add("hostid", String.valueOf(hostId)).add("name", "Trap").add("key_", "app.trap")
						.add("type", 2).add("value_type", 4).add("history", "7d").build()));
				print("trapper create", trapper);
				var update = gm.capture(() -> api.call("item.update", Json.createObjectBuilder().add("itemid", String.valueOf(id[0]))
						.add("name", "CPU load 1m").add("delay", "30s").add("status", 1)
						.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "component").add("value", "system")))
						.build()));
				print("item update", update);
				var delete = gm.capture(() -> api.call("item.delete", Json.createArrayBuilder().add(String.valueOf(id[0])).build()));
				print("item delete", delete);
			} finally {
				api.call("host.delete", Json.createArrayBuilder().add(String.valueOf(hostId)).build());
			}
			return null;
		});
	}

	@Test
	void observeActionWrites() {
		String v = System.getenv("TMON_IT_OBSERVE");
		assumeTrue(v != null, "TMON_IT_OBSERVE not set");
		String url = System.getenv("TMON_IT_ZABBIX" + v + "_URL");
		ZabbixApiClient api = new ZabbixApiClient(System.getenv("TMON_IT_ZABBIX" + v + "_API"), !v.equals("60"))
				.login("Admin", "zabbix");
		ZabbixDatabases.withSingleConnection(url, "zabbix", "zabbix", db -> {
			GoldenMaster gm = new GoldenMaster(db);
			long linux = db.queryOne(PreparedSql.of("select groupid from hstgrp where name = 'Linux servers'"), r -> r.getLong(1)).orElseThrow();
			long admins = db.queryOne(PreparedSql.of("select usrgrpid from usrgrp where name = 'Zabbix administrators'"), r -> r.getLong(1)).orElseThrow();
			long admin = db.queryOne(PreparedSql.of("select userid from users where username = 'Admin'"), r -> r.getLong(1)).orElseThrow();
			String name = "obs-action-" + UUID.randomUUID().toString().substring(0, 6);
			long[] id = new long[1];
			var create = gm.capture(() -> id[0] = ZabbixApiClient.firstId(api.call("action.create", Json.createObjectBuilder()
					.add("name", name).add("eventsource", 0).add("status", 0).add("esc_period", "30m")
					.add("filter", Json.createObjectBuilder().add("evaltype", 0).add("conditions", Json.createArrayBuilder()
							.add(Json.createObjectBuilder().add("conditiontype", 0).add("operator", 0).add("value", String.valueOf(linux)))
							.add(Json.createObjectBuilder().add("conditiontype", 4).add("operator", 5).add("value", "3"))
							.add(Json.createObjectBuilder().add("conditiontype", 26).add("operator", 2).add("value", "prod").add("value2", "env"))))
					.add("operations", Json.createArrayBuilder().add(Json.createObjectBuilder().add("operationtype", 0)
							.add("esc_step_from", 1).add("esc_step_to", 2).add("esc_period", "0")
							.add("opmessage", Json.createObjectBuilder().add("default_msg", 1).add("mediatypeid", "0"))
							.add("opmessage_grp", Json.createArrayBuilder().add(Json.createObjectBuilder().add("usrgrpid", String.valueOf(admins))))
							.add("opmessage_usr", Json.createArrayBuilder().add(Json.createObjectBuilder().add("userid", String.valueOf(admin))))))
					.add("recovery_operations", Json.createArrayBuilder().add(Json.createObjectBuilder().add("operationtype", 11)
							.add("opmessage", Json.createObjectBuilder().add("default_msg", 1))))
					.add("update_operations", Json.createArrayBuilder().add(Json.createObjectBuilder().add("operationtype", 0)
							.add("opmessage", Json.createObjectBuilder().add("default_msg", 0).add("subject", "S").add("message", "M").add("mediatypeid", "1"))
							.add("opmessage_grp", Json.createArrayBuilder().add(Json.createObjectBuilder().add("usrgrpid", String.valueOf(admins))))))
					.build()), "actionids"));
			print("action create", create);
			var update = gm.capture(() -> api.call("action.update", Json.createObjectBuilder().add("actionid", String.valueOf(id[0]))
					.add("name", name + "-r").add("status", 1)
					.add("filter", Json.createObjectBuilder().add("evaltype", 2).add("conditions", Json.createArrayBuilder()
							.add(Json.createObjectBuilder().add("conditiontype", 4).add("operator", 5).add("value", "4"))
							.add(Json.createObjectBuilder().add("conditiontype", 25).add("operator", 0).add("value", "service"))))
					.build()));
			print("action update filter", update);
			var ops = gm.capture(() -> api.call("action.update", Json.createObjectBuilder().add("actionid", String.valueOf(id[0]))
					.add("operations", Json.createArrayBuilder().add(Json.createObjectBuilder().add("operationtype", 0)
							.add("esc_step_from", 1).add("esc_step_to", 1)
							.add("opmessage", Json.createObjectBuilder().add("default_msg", 1).add("mediatypeid", "0"))
							.add("opmessage_usr", Json.createArrayBuilder().add(Json.createObjectBuilder().add("userid", String.valueOf(admin))))))
					.add("recovery_operations", Json.createArrayBuilder())
					.build()));
			print("action update operations", ops);
			var delete = gm.capture(() -> api.call("action.delete", Json.createArrayBuilder().add(String.valueOf(id[0])).build()));
			print("action delete", delete);
			return null;
		});
	}

	@Test
	void observeTemplateLink() {
		String v = System.getenv("TMON_IT_OBSERVE");
		assumeTrue(v != null, "TMON_IT_OBSERVE not set");
		String url = System.getenv("TMON_IT_ZABBIX" + v + "_URL");
		ZabbixApiClient api = new ZabbixApiClient(System.getenv("TMON_IT_ZABBIX" + v + "_API"), !v.equals("60"))
				.login("Admin", "zabbix");
		ZabbixDatabases.withSingleConnection(url, "zabbix", "zabbix", db -> {
			GoldenMaster gm = new GoldenMaster(db);
			long tplGroup = db.queryOne(PreparedSql.of("select groupid from hstgrp where name = 'Templates'"), r -> r.getLong(1)).orElseThrow();
			long linux = db.queryOne(PreparedSql.of("select groupid from hstgrp where name = 'Linux servers'"), r -> r.getLong(1)).orElseThrow();
			String sfx = UUID.randomUUID().toString().substring(0, 6);
			String tpl = "obs-tpl-" + sfx;
			long tplId = ZabbixApiClient.firstId(api.call("template.create", Json.createObjectBuilder().add("host", tpl)
					.add("groups", Json.createArrayBuilder().add(Json.createObjectBuilder().add("groupid", String.valueOf(tplGroup))))
					.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "class").add("value", "os")))
					.build()), "templateids");
			long hostId = ZabbixApiClient.firstId(api.call("host.create", Json.createObjectBuilder().add("host", "obs-tplhost-" + sfx)
					.add("groups", Json.createArrayBuilder().add(Json.createObjectBuilder().add("groupid", String.valueOf(linux))))
					.add("interfaces", Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", 1).add("main", 1)
							.add("useip", 1).add("ip", "192.0.2.88").add("dns", "").add("port", "10050")))
					.build()), "hostids");
			try {
				long a = ZabbixApiClient.firstId(api.call("item.create", Json.createObjectBuilder().add("hostid", String.valueOf(tplId))
						.add("name", "CPU load").add("key_", "system.cpu.load").add("type", 0).add("value_type", 0).add("delay", "1m")
						.add("units", "").add("description", "d")
						.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "component").add("value", "cpu")))
						.add("preprocessing", Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", 1).add("params", "2")
								.add("error_handler", 0).add("error_handler_params", "")))
						.build()), "itemids");
				ZabbixApiClient.firstId(api.call("item.create", Json.createObjectBuilder().add("hostid", String.valueOf(tplId))
						.add("name", "Trap").add("key_", "app.trap").add("type", 2).add("value_type", 3).build()), "itemids");
				long t1 = ZabbixApiClient.firstId(api.call("trigger.create", Json.createObjectBuilder().add("description", "High load")
						.add("expression", "last(/" + tpl + "/system.cpu.load)>5").add("priority", 3)
						.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "scope").add("value", "perf")))
						.build()), "triggerids");
				api.call("trigger.create", Json.createObjectBuilder().add("description", "Trap and load")
						.add("expression", "last(/" + tpl + "/app.trap)>0 and last(/" + tpl + "/system.cpu.load)>1").add("priority", 2)
						.add("dependencies", Json.createArrayBuilder().add(Json.createObjectBuilder().add("triggerid", String.valueOf(t1))))
						.build());
				api.call("graph.create", Json.createObjectBuilder().add("name", "Load").add("width", 900).add("height", 200)
						.add("gitems", Json.createArrayBuilder().add(Json.createObjectBuilder().add("itemid", String.valueOf(a)).add("color", "1A7C11")))
						.build());
				var link = gm.capture(() -> api.call("host.update", Json.createObjectBuilder().add("hostid", String.valueOf(hostId))
						.add("templates", Json.createArrayBuilder().add(Json.createObjectBuilder().add("templateid", String.valueOf(tplId))))
						.build()));
				print("link", link);
				var unlink = gm.capture(() -> api.call("host.update", Json.createObjectBuilder().add("hostid", String.valueOf(hostId))
						.add("templates", Json.createArrayBuilder()).build()));
				print("unlink (keep)", unlink);
				api.call("host.update", Json.createObjectBuilder().add("hostid", String.valueOf(hostId)).add("templates", Json.createArrayBuilder()
						.add(Json.createObjectBuilder().add("templateid", String.valueOf(tplId)))).build());
				print("relink (host already has the items, now not templated)", "see above");
				var clear = gm.capture(() -> api.call("host.update", Json.createObjectBuilder().add("hostid", String.valueOf(hostId))
						.add("templates_clear", Json.createArrayBuilder().add(Json.createObjectBuilder().add("templateid", String.valueOf(tplId))))
						.build()));
				print("unlink and clear", clear);
			} finally {
				api.call("host.delete", Json.createArrayBuilder().add(String.valueOf(hostId)).build());
				api.call("template.delete", Json.createArrayBuilder().add(String.valueOf(tplId)).build());
			}
			return null;
		});
	}

	private static void print(String what, Object diff) {
		System.out.println("[OBSERVE] " + what + ": " + diff.toString().replace("], ", "],\n    "));
	}
}
