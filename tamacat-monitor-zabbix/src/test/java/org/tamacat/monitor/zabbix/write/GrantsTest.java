package org.tamacat.monitor.zabbix.write;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.json.Json;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tamacat.dao.PreparedSql;
import org.tamacat.dao.exception.DaoException;
import org.tamacat.sql.Database;
import org.tamacat.monitor.zabbix.ZabbixDatabases;
import org.tamacat.monitor.zabbix.ZabbixSchema;
import org.tamacat.monitor.zabbix.ZabbixVersion;

/**
 * T-409: the write account of grants/mysql.sql can do what the writers need, and nothing else.
 * Creates the accounts with the root user of the test containers (TMON_IT_ROOT_PASSWORD, default
 * zabbix-root). Test databases only.
 */
class GrantsTest {

	static Stream<Arguments> servers() {
		return Stream.of(Arguments.of("60", ZabbixVersion.V6_0), Arguments.of("70", ZabbixVersion.V7_0), Arguments.of("80", ZabbixVersion.V8_0));
	}

	static String grantsSql(String password) throws IOException {
		return grantsSql("mysql.sql", password);
	}

	static String grantsSql(String file, String password) throws IOException {
		try (InputStream in = GrantsTest.class.getResourceAsStream("/org/tamacat/monitor/zabbix/grants/" + file)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("${db}", "zabbix").replace("${host}", "%")
					.replace("${read_password}", password).replace("${write_password}", password);
		}
	}

	@ParameterizedTest
	@MethodSource("servers")
	void writeAccountHasExactlyTheNeededPrivileges(String v, ZabbixVersion version) throws IOException {
		String url = System.getenv("TMON_IT_ZABBIX" + v + "_URL");
		assumeTrue(url != null, "TMON_IT_ZABBIX" + v + "_URL not set");
		String password = applyGrants(url, version);

		Runnable[] cleanup = { () -> { } };
		ZabbixDatabases.withSingleConnection(url, "tmon_write", password, db -> {
			HostGroupWriter writer = new HostGroupWriter();
			String name = "tmon-grant/" + UUID.randomUUID().toString().substring(0, 8);
			long id = write(db, version, ctx -> writer.create(ctx, name));
			write(db, version, ctx -> {
				writer.rename(ctx, id, name, name + "-r");
				return null;
			});
			// hosts: create, then move to another group (7.0: new host group set with permissions)
			long other = write(db, version, ctx -> writer.create(ctx, name + "-other"));
			HostWriter hosts = new HostWriter();
			String host = "tmon-grant-" + UUID.randomUUID().toString().substring(0, 8);
			long hostId = write(db, version, ctx -> hosts.create(ctx, new HostWriter.HostInput(host, null, "", 0,
					java.util.Set.of(id), java.util.List.of(new HostWriter.Tag("t", "v")),
					new HostWriter.InterfaceInput(1, true, "192.0.2.70", "", "10050"))));
			write(db, version, ctx -> {
				hosts.update(ctx, hostId, new HostWriter.HostUpdate(host, host, "renamed " + host, null, 1, java.util.Set.of(other),
						java.util.List.of(), new HostWriter.InterfaceInput(1, true, "192.0.2.71", "", "10051")));
				return null;
			});
			// items: create and update on that host
			ItemWriter items = new ItemWriter();
			long itemId = write(db, version, ctx -> items.create(ctx, new ItemWriter.ItemInput(hostId, "Ping", "agent.ping", 0, 3,
					"1m", "7d", "365d", "", "", 0, java.util.List.of(new HostWriter.Tag("t", "v")))));
			write(db, version, ctx -> {
				items.update(ctx, itemId, new ItemWriter.ItemUpdate("agent.ping", "Ping", "Ping 2", "30s", null, null, null, null, 1,
						java.util.List.of()));
				return null;
			});
			// trigger actions: create, then update (replaces conditions and operations)
			ActionWriter actions = new ActionWriter();
			long admins = db.queryOne(PreparedSql.of("select usrgrpid from usrgrp where name = 'Zabbix administrators'"),
					r -> r.getLong(1)).orElseThrow();
			long adminUser = db.queryOne(PreparedSql.of("select userid from users where username = 'Admin'"), r -> r.getLong(1))
					.orElseThrow();
			var msg = new ActionWriter.Message(true, null, null, null, java.util.List.of(admins), java.util.List.of());
			long actionId = write(db, version, ctx -> actions.create(ctx, new ActionWriter.ActionInput(name, 0, "1h", true, true, true, 0,
					java.util.List.of(new ActionWriter.Condition(0, 0, String.valueOf(id), "")),
					java.util.List.of(new ActionWriter.Operation(0, 1, 1, "0", msg)),
					java.util.List.of(new ActionWriter.Operation(11, 1, 1, "0", msg)), java.util.List.of())));
			write(db, version, ctx -> {
				actions.update(ctx, actionId, name, new ActionWriter.ActionInput(name + "-r", 1, "30m", true, true, true, 0,
						java.util.List.of(new ActionWriter.Condition(4, 5, "3", "")),
						java.util.List.of(new ActionWriter.Operation(0, 1, 2, "0", new ActionWriter.Message(false, "s", "m", null,
								java.util.List.of(), java.util.List.of(adminUser)))),
						java.util.List.of(), java.util.List.of()));
				return null;
			});
			// deletes (T-408): the action, the item, then the host
			write(db, version, ctx -> {
				actions.delete(ctx, actionId, name + "-r");
				return null;
			});
			long trap = write(db, version, ctx -> items.create(ctx, new ItemWriter.ItemInput(hostId, "Trap", "app.trap", 2, 3, null, "7d",
					"365d", "", "", 0, java.util.List.of())));
			write(db, version, ctx -> {
				items.delete(ctx, trap, "app.trap");
				return null;
			});
			write(db, version, ctx -> {
				hosts.delete(ctx, hostId, host);
				return null;
			});
			cleanup[0] = () -> ZabbixDatabases.withSingleConnection(url, "root",
					System.getenv().getOrDefault("TMON_IT_ROOT_PASSWORD", "zabbix-root"), root -> {
						root.update(PreparedSql.of("delete from actions where actionid = ?", actionId));
						root.update(PreparedSql.of("delete from items where hostid = ?", hostId));
						root.update(PreparedSql.of("delete from hosts where hostid = ?", hostId));
						if (version != ZabbixVersion.V6_0) {
							for (String hash : java.util.List.of(HostGroupSets.hash(java.util.List.of(id)),
									HostGroupSets.hash(java.util.List.of(other)))) {
								for (long hs : root.queryList(PreparedSql.of("select hgsetid from hgset where hash = ?", hash), r -> r.getLong(1))) {
									root.update(PreparedSql.of("delete from host_hgset where hgsetid = ?", hs));
									root.update(PreparedSql.of("delete from permission where hgsetid = ?", hs));
									root.update(PreparedSql.of("delete from hgset_group where hgsetid = ?", hs));
									root.update(PreparedSql.of("delete from hgset where hgsetid = ?", hs));
								}
							}
						}
						root.update(PreparedSql.of("delete from hstgrp where groupid in (?, ?)", id, other));
						return null;
					});
			// tables without grants
			assertThrows(DaoException.class, () -> db.update(PreparedSql.of("update functions set name = name where 1=0")));
			assertThrows(DaoException.class, () -> db.update(PreparedSql.of("update hosts_templates set templateid = templateid where 1=0")));
			assertThrows(DaoException.class, () -> db.update(PreparedSql.of("delete from users where 1=0")));
			assertThrows(DaoException.class, () -> db.update(PreparedSql.of("delete from usrgrp where 1=0")));
			assertThrows(DaoException.class, () -> db.update(PreparedSql.of("delete from media_type where 1=0")));
			assertThrows(DaoException.class, () -> db.update(PreparedSql.of("update config set work_period = work_period where 1=0")));
			assertThrows(DaoException.class, () -> db.update(PreparedSql.of("delete from housekeeper where 1=0")));
			assertThrows(DaoException.class, () -> db.update(PreparedSql.of("update media_type set name = name where 1=0")));
			return null;
		});
		cleanup[0].run();
		ZabbixDatabases.withSingleConnection(url, "tmon_read", password, db -> {
			db.queryOne(PreparedSql.of("select count(*) from hosts"), r -> r.getLong(1));
			assertThrows(DaoException.class, () -> db.update(PreparedSql.of("update hstgrp set name = name where 1=0")));
			return null;
		});
	}

	/** Recreates tmon_read / tmon_write from the grant files with a random password (returned). */
	static String applyGrants(String url, ZabbixVersion version) throws IOException {
		String password = "it-" + UUID.randomUUID();
		String sql = grantsSql(password) + (version == ZabbixVersion.V6_0 ? "" : grantsSql("mysql-7.0.sql", password))
				+ (version == ZabbixVersion.V8_0 ? grantsSql("mysql-8.0.sql", password) : "");
		ZabbixDatabases.withSingleConnection(url, "root", System.getenv().getOrDefault("TMON_IT_ROOT_PASSWORD", "zabbix-root"),
				root -> {
					root.update(PreparedSql.of("drop user if exists 'tmon_write'@'%'"));
					root.update(PreparedSql.of("drop user if exists 'tmon_read'@'%'"));
					String withoutComments = sql.lines().filter(l -> !l.strip().startsWith("--"))
							.reduce("", (a, b) -> a + "\n" + b);
					for (String stmt : withoutComments.split(";")) {
						String st = stmt.strip();
						if (!st.isEmpty()) {
							root.update(new PreparedSql(st, java.util.List.of()));
						}
					}
					return null;
				});
		return password;
	}

	/** T-601: the write account can link, unlink and unlink-and-clear a template (made through the API). */
	@ParameterizedTest
	@MethodSource("servers")
	void writeAccountCanLinkTemplates(String v, ZabbixVersion version) throws IOException {
		String url = System.getenv("TMON_IT_ZABBIX" + v + "_URL");
		String apiUrl = System.getenv("TMON_IT_ZABBIX" + v + "_API");
		assumeTrue(url != null && apiUrl != null, "TMON_IT_ZABBIX" + v + "_URL / _API not set");
		String password = applyGrants(url, version);
		ZabbixApiClient api = new ZabbixApiClient(apiUrl, version != ZabbixVersion.V6_0).login("Admin", "zabbix");
		String sfx = UUID.randomUUID().toString().substring(0, 8);
		String tplName = "tmon-grant-tpl-" + sfx;
		String hostName = "tmon-grant-th-" + sfx;
		long[] ids = new long[2];
		ZabbixDatabases.withSingleConnection(url, "zabbix", "zabbix", db -> {
			long group = db.queryOne(PreparedSql.of("select groupid from hstgrp where name = 'Templates'"), r -> r.getLong(1)).orElseThrow();
			long linux = db.queryOne(PreparedSql.of("select groupid from hstgrp where name = 'Linux servers'"), r -> r.getLong(1)).orElseThrow();
			ids[0] = ZabbixApiClient.firstId(api.call("template.create", Json.createObjectBuilder().add("host", tplName)
					.add("groups", Json.createArrayBuilder().add(Json.createObjectBuilder().add("groupid", String.valueOf(group))))
					.build()), "templateids");
			long item = ZabbixApiClient.firstId(api.call("item.create", Json.createObjectBuilder().add("hostid", String.valueOf(ids[0]))
					.add("name", "Trap").add("key_", "app.trap").add("type", 2).add("value_type", 3)
					.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "t").add("value", "v")))
					.add("preprocessing", Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", 1).add("params", "2")
							.add("error_handler", 0).add("error_handler_params", "")))
					.build()), "itemids");
			long t1 = ZabbixApiClient.firstId(api.call("trigger.create", Json.createObjectBuilder().add("description", "T1")
					.add("expression", "last(/" + tplName + "/app.trap)>0")
					.add("tags", Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", "t").add("value", "v"))).build()),
					"triggerids");
			api.call("trigger.create", Json.createObjectBuilder().add("description", "T2").add("expression", "last(/" + tplName + "/app.trap)>5")
					.add("dependencies", Json.createArrayBuilder().add(Json.createObjectBuilder().add("triggerid", String.valueOf(t1))))
					.build());
			api.call("graph.create", Json.createObjectBuilder().add("name", "G").add("width", 900).add("height", 200)
					.add("gitems", Json.createArrayBuilder().add(Json.createObjectBuilder().add("itemid", String.valueOf(item)).add("color", "1A7C11")))
					.build());
			ids[1] = ZabbixApiClient.firstId(api.call("host.create", Json.createObjectBuilder().add("host", hostName)
					.add("groups", Json.createArrayBuilder().add(Json.createObjectBuilder().add("groupid", String.valueOf(linux))))
					.build()), "hostids");
			return null;
		});
		try {
			ZabbixDatabases.withSingleConnection(url, "tmon_write", password, db -> {
				TemplateLinker linker = new TemplateLinker();
				write(db, version, ctx -> {
					linker.link(ctx, ids[1], hostName, ids[0]);
					return null;
				});
				write(db, version, ctx -> {
					linker.unlink(ctx, ids[1], hostName, ids[0], false);
					return null;
				});
				// the kept copies are ordinary host items now: delete them, link again, then unlink and clear
				write(db, version, ctx -> {
					Deletion.deleteItems(ctx, ctx.db().queryList(PreparedSql.of("select itemid from items where hostid = ?", ids[1]),
							r -> r.getLong(1)));
					return null;
				});
				write(db, version, ctx -> {
					linker.link(ctx, ids[1], hostName, ids[0]);
					return null;
				});
				write(db, version, ctx -> {
					linker.unlink(ctx, ids[1], hostName, ids[0], true);
					return null;
				});
				return null;
			});
		} finally {
			api.call("host.delete", Json.createArrayBuilder().add(String.valueOf(ids[1])).build());
			api.call("template.delete", Json.createArrayBuilder().add(String.valueOf(ids[0])).build());
		}
	}

	private static <R> R write(Database db, ZabbixVersion v, java.util.function.Function<WriteContext, R> work) {
		return db.inTransaction(tx -> work.apply(new WriteContext(tx, null, ZabbixSchema.of(v), new IdAllocator())));
	}
}
