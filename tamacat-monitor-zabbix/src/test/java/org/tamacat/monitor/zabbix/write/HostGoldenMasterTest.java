package org.tamacat.monitor.zabbix.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
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
import org.tamacat.monitor.zabbix.write.HostWriter.HostInput;
import org.tamacat.monitor.zabbix.write.HostWriter.HostUpdate;
import org.tamacat.monitor.zabbix.write.HostWriter.InterfaceInput;
import org.tamacat.monitor.zabbix.write.HostWriter.Tag;

/**
 * Golden-master tests of host writes against the Zabbix API (T-405), including the 7.0 permission
 * cache: a user group with rights on the test groups and a user in it make the API write permission
 * rows for new host group sets. Same environment variables as {@link HostGroupGoldenMasterTest}.
 */
class HostGoldenMasterTest {

	static Stream<Arguments> servers() {
		return Stream.of(Arguments.of("60", ZabbixVersion.V6_0), Arguments.of("70", ZabbixVersion.V7_0), Arguments.of("80", ZabbixVersion.V8_0));
	}

	private static <R> R write(Database db, ZabbixVersion v, Function<WriteContext, R> work) {
		return db.inTransaction(tx -> work.apply(new WriteContext(tx, null, ZabbixSchema.of(v), new IdAllocator())));
	}

	private static JsonArrayBuilder groups(long... ids) {
		JsonArrayBuilder a = Json.createArrayBuilder();
		for (long id : ids) {
			a.add(Json.createObjectBuilder().add("groupid", String.valueOf(id)));
		}
		return a;
	}

	private static JsonArrayBuilder tags(String tag, String value) {
		return Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag", tag).add("value", value));
	}

	/** Removes host group sets of exactly these groups that no host uses (7.0), so each run starts equal. */
	private static void dropUnusedSet(Database db, ZabbixVersion v, long... groupIds) {
		if (v == ZabbixVersion.V6_0) {
			return;
		}
		String hash = HostGroupSets.hash(java.util.Arrays.stream(groupIds).boxed().toList());
		for (long id : db.queryList(PreparedSql.of("select hgsetid from hgset where hash = ? "
				+ "and not exists (select 1 from host_hgset hh where hh.hgsetid = hgset.hgsetid)", hash), r -> r.getLong(1))) {
			db.update(PreparedSql.of("delete from permission where hgsetid = ?", id));
			db.update(PreparedSql.of("delete from hgset_group where hgsetid = ?", id));
			db.update(PreparedSql.of("delete from hgset where hgsetid = ?", id));
		}
	}

	/** hostinterface.update (6.0 / 7.0); 8.0 has no hostinterface API: host.update with the interfaces. */
	private static void updateInterface(ZabbixApiClient api, ZabbixVersion version, long hostId, long ifid, String ip, String port) {
		if (version != ZabbixVersion.V8_0) {
			api.call("hostinterface.update", Json.createObjectBuilder().add("interfaceid", String.valueOf(ifid))
					.add("ip", ip).add("port", port).build());
			return;
		}
		api.call("host.update", Json.createObjectBuilder().add("hostid", String.valueOf(hostId))
				.add("interfaces", Json.createArrayBuilder().add(Json.createObjectBuilder().add("interfaceid", String.valueOf(ifid))
						.add("type", 1).add("main", 1).add("useip", 1).add("ip", ip).add("dns", "").add("port", port)))
				.build());
	}

	@ParameterizedTest
	@MethodSource("servers")
	void hostWritesEqualTheZabbixApi(String v, ZabbixVersion version) {
		String url = System.getenv("TMON_IT_ZABBIX" + v + "_URL");
		String apiUrl = System.getenv("TMON_IT_ZABBIX" + v + "_API");
		assumeTrue(url != null && apiUrl != null, "TMON_IT_ZABBIX" + v + "_URL / _API not set");
		ZabbixApiClient api = new ZabbixApiClient(apiUrl, version != ZabbixVersion.V6_0).login("Admin", "zabbix");
		String suffix = UUID.randomUUID().toString().substring(0, 6);
		boolean v70 = version != ZabbixVersion.V6_0;

		ZabbixDatabases.withSingleConnection(url, "zabbix", "zabbix", db -> {
			long a = ZabbixApiClient.firstId(api.call("hostgroup.create", Json.createObjectBuilder().add("name", "gm-host-a-" + suffix).build()), "groupids");
			long b = ZabbixApiClient.firstId(api.call("hostgroup.create", Json.createObjectBuilder().add("name", "gm-host-b-" + suffix).build()), "groupids");
			long usrgrp = 0;
			long user = 0;
			if (v70) {
				usrgrp = ZabbixApiClient.firstId(api.call("usergroup.create", Json.createObjectBuilder().add("name", "gm-ug-" + suffix)
						.add("hostgroup_rights", Json.createArrayBuilder()
								.add(Json.createObjectBuilder().add("id", String.valueOf(a)).add("permission", 3))
								.add(Json.createObjectBuilder().add("id", String.valueOf(b)).add("permission", 2)))
						.build()), "usrgrpids");
				user = ZabbixApiClient.firstId(api.call("user.create", Json.createObjectBuilder().add("username", "gm-user-" + suffix)
						.add("passwd", "Gm-" + UUID.randomUUID()).add("roleid", "1")
						.add("usrgrps", Json.createArrayBuilder().add(Json.createObjectBuilder().add("usrgrpid", String.valueOf(usrgrp))))
						.build()), "userids");
			}
			try {
				GoldenMaster gm = new GoldenMaster(db);
				HostWriter writer = new HostWriter();
				String host = "gm-host-" + suffix;
				long[] id = new long[1];
				JsonObject createParams = Json.createObjectBuilder().add("host", host).add("name", host + " visible")
						.add("description", "golden master").add("groups", groups(a))
						.add("interfaces", Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", 1).add("main", 1)
								.add("useip", 1).add("ip", "192.0.2.60").add("dns", "").add("port", "10050")))
						.add("tags", tags("env", "test")).build();

				// create
				var byApi = gm.capture(() -> id[0] = ZabbixApiClient.firstId(api.call("host.create", createParams), "hostids"));
				api.call("host.delete", Json.createArrayBuilder().add(String.valueOf(id[0])).build());
				dropUnusedSet(db, version, a);
				var byWriter = gm.capture(() -> id[0] = write(db, version, ctx -> writer.create(ctx, new HostInput(host, host + " visible",
						"golden master", 0, Set.of(a), List.of(new Tag("env", "test")), new InterfaceInput(1, true, "192.0.2.60", "", "10050")))));
				if (v70) {
					assertTrue(byApi.containsKey("permission"), "permission rows expected on 7.0: " + byApi);
				}
				assertEquals(byApi, byWriter);

				// update: name, status, groups (+b), tags
				byApi = gm.capture(() -> api.call("host.update", Json.createObjectBuilder().add("hostid", String.valueOf(id[0]))
						.add("name", host + " renamed").add("status", 1).add("groups", groups(a, b)).add("tags", tags("env", "prod")).build()));
				api.call("host.update", Json.createObjectBuilder().add("hostid", String.valueOf(id[0])).add("name", host + " visible")
						.add("status", 0).add("groups", groups(a)).add("tags", tags("env", "test")).build());
				dropUnusedSet(db, version, a, b);
				byWriter = gm.capture(() -> write(db, version, ctx -> {
					writer.update(ctx, id[0], new HostUpdate(host, host + " visible", host + " renamed", null, 1, Set.of(a, b),
							List.of(new Tag("env", "prod")), null));
					return null;
				}));
				assertEquals(byApi, byWriter);

				// main interface address and port
				long ifid = db.queryOne(PreparedSql.of("select interfaceid from interface where hostid = ?", id[0]), r -> r.getLong(1)).orElseThrow();
				byApi = gm.capture(() -> updateInterface(api, version, id[0], ifid, "192.0.2.61", "10051"));
				updateInterface(api, version, id[0], ifid, "192.0.2.60", "10050");
				byWriter = gm.capture(() -> write(db, version, ctx -> {
					writer.update(ctx, id[0], new HostUpdate(host, host + " renamed", null, null, null, null, null,
							new InterfaceInput(1, true, "192.0.2.61", "", "10051")));
					return null;
				}));
				assertEquals(byApi, byWriter);

				// refusals
				assertThrows(ConflictException.class, () -> write(db, version, ctx -> {
					writer.update(ctx, id[0], new HostUpdate(host, "stale name", "x", null, null, null, null, null));
					return null;
				}));
				assertThrows(ConflictException.class, () -> write(db, version, ctx -> writer.create(ctx, new HostInput(host, "other",
						"", 0, Set.of(a), List.of(), null))));
				assertThrows(ValidationException.class, () -> write(db, version, ctx -> writer.create(ctx, new HostInput("bad/name", null,
						"", 0, Set.of(a), List.of(), null))));
				assertThrows(ValidationException.class, () -> write(db, version, ctx -> writer.create(ctx, new HostInput("gm-nogroup-" + suffix,
						null, "", 0, Set.of(), List.of(), null))));

				api.call("host.delete", Json.createArrayBuilder().add(String.valueOf(id[0])).build());
			} finally {
				for (long h : db.queryList(PreparedSql.of("select hostid from hosts where host like ?", "gm-host-" + suffix + "%"),
						r -> r.getLong(1))) {
					api.call("host.delete", Json.createArrayBuilder().add(String.valueOf(h)).build());
				}
				if (v70) {
					api.call("user.delete", Json.createArrayBuilder().add(String.valueOf(user)).build());
					api.call("usergroup.delete", Json.createArrayBuilder().add(String.valueOf(usrgrp)).build());
				}
				dropUnusedSet(db, version, a);
				dropUnusedSet(db, version, a, b);
				api.call("hostgroup.delete", Json.createArrayBuilder().add(String.valueOf(a)).add(String.valueOf(b)).build());
			}
			return null;
		});
	}
}
