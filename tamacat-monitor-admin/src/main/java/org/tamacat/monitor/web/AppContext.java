package org.tamacat.monitor.web;

import java.time.Clock;

import jakarta.servlet.ServletContext;

import org.tamacat.sql.Database;
import org.tamacat.sql.pool.ConnectionPool;
import org.tamacat.monitor.config.AppConfig;
import org.tamacat.monitor.config.DatabaseFactory.PooledDatabase;
import org.tamacat.monitor.user.UserService;
import org.tamacat.monitor.zabbix.ZabbixDatabases;
import org.tamacat.monitor.zabbix.ZabbixServerService;
import org.tamacat.monitor.zabbix.query.DashboardService;
import org.tamacat.monitor.zabbix.query.ZabbixQueryService;
import org.tamacat.monitor.zabbix.write.HostGroupWriteService;
import org.tamacat.monitor.zabbix.write.HostWriteService;
import org.tamacat.monitor.zabbix.write.ItemWriteService;
import org.tamacat.monitor.zabbix.write.ActionWriteService;
import org.tamacat.monitor.zabbix.write.TemplateLinkService;
import org.tamacat.monitor.user.MfaService;
import org.tamacat.monitor.user.PasskeyService;

/** Application-wide objects, stored in the ServletContext. */
public final class AppContext implements AutoCloseable {

	private final AppConfig config;
	private final PooledDatabase central;
	private final ZabbixDatabases zabbixDatabases;
	private final UserService users;
	private final ZabbixServerService servers;
	private final ZabbixQueryService zabbix;
	private final DashboardService dashboard;
	private final HostGroupWriteService hostGroupWrites;
	private final HostWriteService hostWrites;
	private final ItemWriteService itemWrites;
	private final ActionWriteService actionWrites;
	private final TemplateLinkService templateLinks;
	private final MfaService mfa;
	private final PasskeyService passkeys;
	private final Clock clock;
	private final String version;

	AppContext(AppConfig config, PooledDatabase central, ZabbixDatabases zabbixDatabases, UserService users,
			ZabbixServerService servers, ZabbixQueryService zabbix, DashboardService dashboard, HostGroupWriteService hostGroupWrites, HostWriteService hostWrites, ItemWriteService itemWrites, ActionWriteService actionWrites, TemplateLinkService templateLinks, MfaService mfa, PasskeyService passkeys, Clock clock,
			String version) {
		this.config = config;
		this.central = central;
		this.zabbixDatabases = zabbixDatabases;
		this.users = users;
		this.servers = servers;
		this.zabbix = zabbix;
		this.dashboard = dashboard;
		this.hostGroupWrites = hostGroupWrites;
		this.hostWrites = hostWrites;
		this.itemWrites = itemWrites;
		this.actionWrites = actionWrites;
		this.templateLinks = templateLinks;
		this.mfa = mfa;
		this.passkeys = passkeys;
		this.clock = clock;
		this.version = version;
	}

	public static AppContext of(ServletContext sc) {
		Object ctx = sc.getAttribute(AppLifecycleListener.APP_CONTEXT);
		if (ctx instanceof AppContext app) {
			return app;
		}
		throw new IllegalStateException("Application is not initialized");
	}

	public AppConfig config() {
		return config;
	}

	/** The central (tamacat-monitor) database. */
	public Database centralDb() {
		return central.database();
	}

	public ConnectionPool centralPool() {
		return central.pool();
	}

	public UserService users() {
		return users;
	}

	public ZabbixServerService servers() {
		return servers;
	}

	/** Read access to the Zabbix servers on behalf of a user. */
	public ZabbixQueryService zabbix() {
		return zabbix;
	}

	public DashboardService dashboard() {
		return dashboard;
	}

	/** Host group writes on the Zabbix DBs (P4). */
	public HostGroupWriteService hostGroupWrites() {
		return hostGroupWrites;
	}

	/** Host writes on the Zabbix DBs (P4). */
	public HostWriteService hostWrites() {
		return hostWrites;
	}

	/** Item writes on the Zabbix DBs (P4). */
	public ItemWriteService itemWrites() {
		return itemWrites;
	}

	/** Trigger action writes on the Zabbix DBs (P4). */
	public ActionWriteService actionWrites() {
		return actionWrites;
	}

	/** Template links of hosts (P6). */
	public TemplateLinkService templateLinks() {
		return templateLinks;
	}

	/** Two-factor authentication (P6). */
	public MfaService mfa() {
		return mfa;
	}

	public PasskeyService passkeys() {
		return passkeys;
	}

	public Clock clock() {
		return clock;
	}

	/** Application version, used to bust browser caches of static files. */
	public String version() {
		return version;
	}

	@Override
	public void close() {
		zabbixDatabases.close();
		central.close();
	}
}
