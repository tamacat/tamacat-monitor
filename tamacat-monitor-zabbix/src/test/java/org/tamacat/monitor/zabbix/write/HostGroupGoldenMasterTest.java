package org.tamacat.monitor.zabbix.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.json.Json;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Database;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.zabbix.ZabbixDatabases;
import org.tamacat.monitor.zabbix.ZabbixSchema;
import org.tamacat.monitor.zabbix.ZabbixVersion;

/**
 * Golden-master tests of host group writes against the Zabbix API (T-401 / T-404), and the id
 * allocation under concurrency (T-402).
 * <pre>
 * TMON_IT_ZABBIX60_URL=jdbc:mysql://127.0.0.1:13360/zabbix  TMON_IT_ZABBIX60_API=http://127.0.0.1:18060/api_jsonrpc.php
 * TMON_IT_ZABBIX70_URL=jdbc:mysql://127.0.0.1:13370/zabbix  TMON_IT_ZABBIX70_API=http://127.0.0.1:18070/api_jsonrpc.php
 * </pre>
 * Uses the default "Admin" / "zabbix" API login of the test containers. Test databases only.
 */
class HostGroupGoldenMasterTest {

	static Stream<Arguments> servers() {
		return Stream.of(Arguments.of("60", ZabbixVersion.V6_0), Arguments.of("70", ZabbixVersion.V7_0), Arguments.of("80", ZabbixVersion.V8_0));
	}

	private static String env(String name) {
		return System.getenv(name);
	}

	private static <R> R withDb(String v, Function<Database, R> work) {
		String url = env("TMON_IT_ZABBIX" + v + "_URL");
		assumeTrue(url != null && env("TMON_IT_ZABBIX" + v + "_API") != null, "TMON_IT_ZABBIX" + v + "_URL / _API not set");
		return ZabbixDatabases.withSingleConnection(url, System.getenv().getOrDefault("TMON_IT_USER", "zabbix"),
				System.getenv().getOrDefault("TMON_IT_PASSWORD", "zabbix"), work);
	}

	private static ZabbixApiClient api(String v, ZabbixVersion version) {
		return new ZabbixApiClient(env("TMON_IT_ZABBIX" + v + "_API"), version != ZabbixVersion.V6_0).login("Admin", "zabbix");
	}

	private static <R> R write(Database db, ZabbixVersion v, Function<WriteContext, R> work) {
		return db.inTransaction(tx -> work.apply(new WriteContext(tx, null, ZabbixSchema.of(v), new IdAllocator())));
	}

	@ParameterizedTest
	@MethodSource("servers")
	void hostGroupWritesEqualTheZabbixApi(String v, ZabbixVersion version) {
		withDb(v, db -> {
			ZabbixApiClient api = api(v, version);
			GoldenMaster gm = new GoldenMaster(db);
			HostGroupWriter writer = new HostGroupWriter();
			String name = "tmon-gm/" + UUID.randomUUID().toString().substring(0, 8);
			long[] id = new long[1];

			// create
			var byApi = gm.capture(() -> id[0] = ZabbixApiClient.firstId(
					api.call("hostgroup.create", Json.createObjectBuilder().add("name", name).build()), "groupids"));
			api.call("hostgroup.delete", Json.createArrayBuilder().add(String.valueOf(id[0])).build());
			var byWriter = gm.capture(() -> id[0] = write(db, version, ctx -> writer.create(ctx, name)));
			if (System.getenv("TMON_IT_GM_DEBUG") != null) {
				System.out.println("[GM " + v + "] create by API:    " + byApi);
				System.out.println("[GM " + v + "] create by writer: " + byWriter);
			}
			assertEquals(Set.of("hstgrp"), GoldenMaster.tables(byApi), "API changes: " + byApi);
			assertEquals(byApi, byWriter);
			long nextId = db.queryOne(PreparedSql.of("select nextid from ids where table_name = 'hstgrp' and field_name = 'groupid'"),
					r -> r.getLong(1)).orElseThrow();
			assertTrue(nextId >= id[0], "ids.nextid holds the last id");

			// rename
			String renamed = name + "-r";
			byApi = gm.capture(() -> api.call("hostgroup.update", Json.createObjectBuilder()
					.add("groupid", String.valueOf(id[0])).add("name", renamed).build()));
			api.call("hostgroup.update", Json.createObjectBuilder().add("groupid", String.valueOf(id[0])).add("name", name).build());
			byWriter = gm.capture(() -> write(db, version, ctx -> {
				writer.rename(ctx, id[0], name, renamed);
				return null;
			}));
			assertEquals(byApi, byWriter);

			// delete
			byApi = gm.capture(() -> api.call("hostgroup.delete", Json.createArrayBuilder().add(String.valueOf(id[0])).build()));
			long id2 = ZabbixApiClient.firstId(api.call("hostgroup.create", Json.createObjectBuilder().add("name", renamed).build()),
					"groupids");
			byWriter = gm.capture(() -> write(db, version, ctx -> {
				writer.delete(ctx, id2, renamed);
				return null;
			}));
			if (System.getenv("TMON_IT_GM_DEBUG") != null) {
				System.out.println("[GM " + v + "] delete by API:    " + byApi);
				System.out.println("[GM " + v + "] delete by writer: " + byWriter);
			}
			assertEquals(byApi, byWriter);
			return null;
		});
	}

	@ParameterizedTest
	@MethodSource("servers")
	void unsafeOrStaleChangesAreRefused(String v, ZabbixVersion version) {
		withDb(v, db -> {
			HostGroupWriter writer = new HostGroupWriter();
			long zabbixServers = db.queryOne(PreparedSql.of("select groupid from hstgrp where name = 'Zabbix servers'"),
					r -> r.getLong(1)).orElseThrow();
			// has a host
			assertThrows(ConflictException.class, () -> write(db, version, ctx -> {
				writer.delete(ctx, zabbixServers, "Zabbix servers");
				return null;
			}));
			// duplicate name
			assertThrows(ConflictException.class, () -> write(db, version, ctx -> writer.create(ctx, "Zabbix servers")));
			// changed meanwhile: the expected name no longer matches
			String name = "tmon-gm/stale-" + UUID.randomUUID().toString().substring(0, 8);
			long id = write(db, version, ctx -> writer.create(ctx, name));
			ConflictException e = assertThrows(ConflictException.class, () -> write(db, version, ctx -> {
				writer.rename(ctx, id, name + "-seen-by-user", name + "-new");
				return null;
			}));
			assertEquals("error.write.conflict", e.messageKey());
			write(db, version, ctx -> {
				writer.delete(ctx, id, name);
				return null;
			});
			return null;
		});
	}

	@ParameterizedTest
	@MethodSource("servers")
	void concurrentAllocationsNeverCollide(String v, ZabbixVersion version) throws Exception {
		String url = env("TMON_IT_ZABBIX" + v + "_URL");
		assumeTrue(url != null, "TMON_IT_ZABBIX" + v + "_URL not set");
		var pool = new org.tamacat.sql.pool.ConnectionPool(org.tamacat.sql.pool.PoolConfig.builder("it").minIdle(0).maxSize(10).build(),
				new org.tamacat.sql.pool.DriverConnectionFactory("com.mysql.cj.jdbc.Driver", url, "zabbix", () -> "zabbix", null));
		try (pool) {
			Database db = new Database("it", pool, org.tamacat.sql.dialect.SqlDialect.MYSQL);
			db.update(PreparedSql.of("create table if not exists tmon_it_seq (id bigint primary key)"));
			db.update(PreparedSql.of("delete from ids where table_name = 'tmon_it_seq'"));
			IdAllocator ids = new IdAllocator();
			Set<Long> seen = ConcurrentHashMap.newKeySet();
			try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
				List<Future<?>> fs = new java.util.ArrayList<>();
				for (int i = 0; i < 40; i++) {
					fs.add(ex.submit(() -> db.inTransaction(tx -> {
						long id = ids.reserve(tx, "tmon_it_seq", "id", 1);
						tx.update(PreparedSql.of("insert into tmon_it_seq (id) values (?)", id));
						seen.add(id);
						return null;
					})));
				}
				for (Future<?> f : fs) {
					f.get();
				}
			} finally {
				db.update(PreparedSql.of("drop table tmon_it_seq"));
				db.update(PreparedSql.of("delete from ids where table_name = 'tmon_it_seq'"));
			}
			assertEquals(40, seen.size());
			assertEquals(new HashSet<>(java.util.stream.LongStream.rangeClosed(1, 40).boxed().toList()), seen);
		}
		Map.of(); // keep imports minimal
	}
}
