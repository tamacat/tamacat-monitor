package org.tamacat.monitor.zabbix;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tamacat.dao.Page;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Database;
import org.tamacat.sql.SqlListener;
import org.tamacat.sql.dialect.SqlDialect;
import org.tamacat.sql.pool.ConnectionPool;
import org.tamacat.sql.pool.DriverConnectionFactory;
import org.tamacat.sql.pool.PoolConfig;
import org.tamacat.monitor.audit.AuditLog;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.security.SecretCipher;
import org.tamacat.monitor.server.ZabbixServer;
import org.tamacat.monitor.store.migration.Migrator;
import org.tamacat.monitor.user.Actor;
import org.tamacat.monitor.zabbix.ZabbixServerService.ServerInput;

public class ZabbixServerServiceTest {

	public static int getLock(String name, int timeout) {
		return 1;
	}

	public static int releaseLock(String name) {
		return 1;
	}

	private ConnectionPool pool;
	private Database central;
	private ZabbixDatabases pools;
	private ZabbixServerService service;
	private final Actor admin = new Actor(1, "admin", "127.0.0.1");

	@BeforeEach
	void setUp() {
		String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE";
		pool = new ConnectionPool(PoolConfig.builder("central").maxSize(4).build(),
				new DriverConnectionFactory("org.h2.Driver", url, "sa", () -> "", null));
		central = new Database("central", pool, SqlDialect.MYSQL, Duration.ofSeconds(10), SqlListener.none());
		central.update(PreparedSql.of("create alias get_lock for \"" + getClass().getName() + ".getLock\""));
		central.update(PreparedSql.of("create alias release_lock for \"" + getClass().getName() + ".releaseLock\""));
		new Migrator(central, getClass().getClassLoader()).migrate();
		pools = new ZabbixDatabases(2, Duration.ofSeconds(5));
		service = new ZabbixServerService(central, new SecretCipher(new byte[32]), pools, new AuditLog(Clock.systemUTC()),
				Clock.systemUTC());
	}

	@AfterEach
	void tearDown() {
		pools.close();
		pool.close();
	}

	private static ServerInput input(String name, String password) {
		return new ServerInput(name, "test", "jdbc:mysql://127.0.0.1:1/zabbix?connectTimeout=1000", "zbx_read",
				password == null ? null : password.toCharArray(), null, null, false, true, true);
	}

	@Test
	void createStoresEncryptedPasswordAndUnreachableStatus() {
		ZabbixServer s = service.create(input("z1", "read-secret"), admin);
		assertEquals("ERROR", s.lastStatus());
		assertFalse(ZabbixServerService.writable(s));
		byte[] stored = central.queryOne(PreparedSql.of("select db_read_password from zabbix_servers"), r -> r.getBytes(1)).orElseThrow();
		assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains("read-secret"));
		assertEquals(1L, central.queryOne(PreparedSql.of("select count(*) from audit_log where target_type = 'zabbix_server'"),
				r -> r.getLong(1)).orElseThrow());
	}

	@Test
	void updateKeepsPasswordWhenEmptyAndRejectsDuplicates() {
		ZabbixServer s = service.create(input("z1", "read-secret"), admin);
		service.create(input("z2", "other"), admin);
		byte[] before = central.queryOne(PreparedSql.of("select db_read_password from zabbix_servers where server_id = ?", s.serverId()),
				r -> r.getBytes(1)).orElseThrow();
		service.update(s.serverId(), input("z1-renamed", null), admin);
		byte[] after = central.queryOne(PreparedSql.of("select db_read_password from zabbix_servers where server_id = ?", s.serverId()),
				r -> r.getBytes(1)).orElseThrow();
		assertArrayEquals(before, after);
		assertEquals("z1-renamed", service.get(s.serverId()).name());
		assertThrows(ConflictException.class, () -> service.update(s.serverId(), input("z2", null), admin));
		assertThrows(ConflictException.class, () -> service.create(input("z2", "x"), admin));
		service.delete(s.serverId(), admin);
		assertEquals(1, service.findAll().size());
	}

	@Test
	void urlPolicyRejectsCredentialsAndRiskyOptions() {
		JdbcUrlPolicy.validate("jdbc:mysql://zabbix-db:3306/zabbix", "dbUrl");
		JdbcUrlPolicy.validate("jdbc:mysql://db.example.com/zabbix?sslMode=VERIFY_IDENTITY&connectTimeout=5000", "dbUrl");
		JdbcUrlPolicy.validate("jdbc:postgresql://pg:5432/zabbix?sslmode=require", "dbUrl");
		JdbcUrlPolicy.validate("jdbc:mysql://h/zabbix?trustCertificateKeyStoreUrl=file:/etc/monitor/trust.p12", "dbUrl");
		for (String bad : List.of("jdbc:mysql://h/zabbix?allowLoadLocalInfile=true", "jdbc:mysql://h/zabbix?user=root&password=x",
				"jdbc:mysql://h/zabbix?autoDeserialize=true", "jdbc:mysql://user:pw@h/zabbix", "jdbc:h2:mem:x",
				"jdbc:mysql://h1,h2/zabbix", "jdbc:mysql://h/zabbix?allowMultiQueries=true", "",
				// the driver would fetch the keystore from any address
				"jdbc:mysql://h/zabbix?trustCertificateKeyStoreUrl=http://169.254.169.254/x",
				"jdbc:mysql://h/zabbix?trustCertificateKeyStoreUrl=file:/etc/../x")) {
			assertThrows(ValidationException.class, () -> JdbcUrlPolicy.validate(bad, "dbUrl"), bad);
		}
		assertThrows(ValidationException.class, () -> service.create(new ServerInput("x", null,
				"jdbc:mysql://h/zabbix?allowLoadLocalInfile=true", "u", "p".toCharArray(), null, null, false, true, true), admin));
	}

	@Test
	void hostGroupsExcludeTemplateGroupsOn70() {
		central.update(PreparedSql.of("create table hstgrp (groupid bigint, name varchar(255), type int)"));
		central.update(PreparedSql.of("insert into hstgrp values (1, 'Linux servers', 0), (2, 'Templates/OS', 1), (3, 'linux db', 0)"));
		List<HostGroup> g70 = new HostGroupReader(ZabbixSchema.of(ZabbixVersion.V7_0)).find(central, "LINUX", Page.first(10));
		assertEquals(List.of(new HostGroup(3, "linux db"), new HostGroup(1, "Linux servers")).size(), g70.size());
		assertTrue(g70.stream().noneMatch(g -> g.name().startsWith("Templates")));
		assertEquals(2, new HostGroupReader(ZabbixSchema.of(ZabbixVersion.V7_0)).find(central, null, Page.first(10)).size());
		assertEquals(3, new HostGroupReader(ZabbixSchema.of(ZabbixVersion.V6_0)).find(central, null, Page.first(10)).size());
	}
}
