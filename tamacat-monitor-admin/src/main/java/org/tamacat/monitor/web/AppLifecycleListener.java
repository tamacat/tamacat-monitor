package org.tamacat.monitor.web;

import java.time.Clock;
import java.time.Duration;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.annotation.WebListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tamacat.monitor.audit.AuditLog;
import org.tamacat.monitor.config.AppConfig;
import org.tamacat.monitor.config.DatabaseFactory;
import org.tamacat.monitor.config.DatabaseFactory.PooledDatabase;
import org.tamacat.monitor.security.PasswordHasher;
import org.tamacat.monitor.security.SecretCipher;
import org.tamacat.monitor.store.migration.Migrator;
import org.tamacat.monitor.user.MfaService;
import org.tamacat.monitor.user.PasskeyService;
import org.tamacat.monitor.security.webauthn.WebAuthn;
import org.tamacat.monitor.user.UserService;
import org.tamacat.monitor.user.UserService.LoginPolicy;
import org.tamacat.monitor.zabbix.ZabbixDatabases;
import org.tamacat.monitor.zabbix.ZabbixServerService;
import org.tamacat.monitor.zabbix.query.DashboardService;
import org.tamacat.monitor.zabbix.query.ZabbixQueryService;
import org.tamacat.monitor.zabbix.write.HostGroupWriteService;
import org.tamacat.monitor.zabbix.write.HostWriteService;
import org.tamacat.monitor.zabbix.write.ItemWriteService;
import org.tamacat.monitor.zabbix.write.ActionWriteService;
import org.tamacat.monitor.zabbix.write.TemplateLinkService;
import org.tamacat.monitor.zabbix.write.ZabbixWriteService;

/**
 * Starts and stops the application: configuration, central DB pool, schema migration,
 * services, initial administrator, session cookie settings. Startup fails (the context does
 * not start) when the central DB is not reachable, a migration fails or the secret key is missing.
 */
@WebListener
public class AppLifecycleListener implements ServletContextListener {

	private static final Logger LOG = LoggerFactory.getLogger(AppLifecycleListener.class);

	/** ServletContext attribute holding the {@link AppContext}. */
	public static final String APP_CONTEXT = AppContext.class.getName();

	/**
	 * Passkeys (T-801) are on when {@code webauthn.rp-id} is set: the domain of the tamacat-monitor URL (e.g.
	 * {@code monitor.example.com}; {@code localhost} for development). {@code webauthn.origins} lists the
	 * accepted origins, comma-separated (default {@code https://<rp-id>}).
	 */
	static WebAuthn webAuthn(AppConfig config) {
		return config.get("webauthn.rp-id").filter(id -> !id.isBlank()).map(id -> {
			java.util.Set<String> origins = new java.util.LinkedHashSet<>();
			for (String o : config.get("webauthn.origins", "https://" + id.strip()).split(",")) {
				if (!o.isBlank()) {
					origins.add(o.strip());
				}
			}
			LOG.info("Passkeys enabled: RP ID {}, origins {}", id.strip(), origins);
			return new WebAuthn(new WebAuthn.RelyingParty(id.strip(), config.get("webauthn.rp-name", "tamacat-monitor"), origins));
		}).orElse(null);
	}

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		ServletContext sc = sce.getServletContext();
		JdbcDriverCleanup.beforeStart();
		AppConfig config = AppConfig.load();
		configureSessionCookie(sc, config);

		PooledDatabase central = null;
		ZabbixDatabases zabbixDatabases = null;
		try {
			SecretCipher cipher = SecretCipher.fromConfig(config);
			central = DatabaseFactory.create(config, "db", "central");
			if (config.getBoolean("db.migrate", true)) {
				new Migrator(central.database(), AppLifecycleListener.class.getClassLoader()).migrate();
			}
			Clock clock = Clock.systemUTC();
			AuditLog audit = new AuditLog(clock);
			PasswordHasher hasher = new PasswordHasher(config.getInt("security.pbkdf2-iterations", PasswordHasher.DEFAULT_ITERATIONS));
			LoginPolicy policy = new LoginPolicy(config.getInt("security.max-login-failures", 5),
					config.getSeconds("security.lock-seconds", Duration.ofMinutes(15)),
					config.getInt("security.max-login-failures-per-ip", 20),
					config.getSeconds("security.ip-window-seconds", Duration.ofMinutes(15)));
			UserService users = new UserService(central.database(), hasher, audit, clock, policy);
			MfaService mfa = new MfaService(central.database(), cipher, hasher, audit, clock, policy,
					config.get("mfa.issuer", "tamacat-monitor"));
			PasskeyService passkeys = new PasskeyService(central.database(), webAuthn(config), hasher, audit, clock, policy);
			zabbixDatabases = new ZabbixDatabases(config.getInt("zabbix.pool.max-size", 5),
					config.getSeconds("zabbix.query-timeout", Duration.ofSeconds(30)));
			ZabbixServerService servers = new ZabbixServerService(central.database(), cipher, zabbixDatabases, audit, clock);

			ZabbixQueryService zabbix = new ZabbixQueryService(servers, users,
					config.getSeconds("zabbix.server-timeout", Duration.ofSeconds(20)));
			DashboardService dashboard = new DashboardService(zabbix, servers, central.database(),
					config.getSeconds("dashboard.cache-seconds", Duration.ofSeconds(30)), clock);
			ZabbixWriteService writes = new ZabbixWriteService(servers, central.database(), audit);
			HostGroupWriteService hostGroupWrites = new HostGroupWriteService(zabbix, writes);
			HostWriteService hostWrites = new HostWriteService(zabbix, writes);
			ItemWriteService itemWrites = new ItemWriteService(zabbix, writes);
			ActionWriteService actionWrites = new ActionWriteService(zabbix, writes);
			TemplateLinkService templateLinks = new TemplateLinkService(zabbix, writes);

			bootstrapAdmin(config, users);
			String version = config.get("app.version", AppLifecycleListener.class.getPackage().getImplementationVersion() != null
					? AppLifecycleListener.class.getPackage().getImplementationVersion() : String.valueOf(System.currentTimeMillis()));
			sc.setAttribute(APP_CONTEXT, new AppContext(config, central, zabbixDatabases, users, servers, zabbix, dashboard, hostGroupWrites, hostWrites, itemWrites, actionWrites, templateLinks, mfa, passkeys, clock,
					version));
		} catch (RuntimeException e) {
			if (zabbixDatabases != null) {
				zabbixDatabases.close();
			}
			if (central != null) {
				central.close();
			}
			JdbcDriverCleanup.afterStop(AppLifecycleListener.class.getClassLoader());
			LOG.error("Startup failed: {}", e.toString());
			throw e;
		}
		LOG.info("tamacat-monitor started (context path '{}')", sc.getContextPath());
	}

	/** Creates the first administrator from {@code admin.initial-password(.file)} when there is no user. */
	private static void bootstrapAdmin(AppConfig config, UserService users) {
		var password = config.secret("admin.initial-password");
		String loginId = config.get("admin.login-id", "admin");
		if (password.isPresent()) {
			users.bootstrapAdmin(loginId, password.get().toCharArray());
		} else if (users.findAll().isEmpty()) {
			LOG.warn("No user exists. Set TMON_ADMIN_INITIAL_PASSWORD (or _FILE) to create the initial administrator.");
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		Object ctx = sce.getServletContext().getAttribute(APP_CONTEXT);
		if (ctx instanceof AppContext app) {
			app.close();
		}
		JdbcDriverCleanup.afterStop(AppLifecycleListener.class.getClassLoader());
		LOG.info("tamacat-monitor stopped");
	}

	/**
	 * Session cookie: HttpOnly, SameSite=Strict, Secure unless {@code session.cookie-secure=false}
	 * (plain-HTTP development only). Uses the Servlet 6 API, no Tomcat-specific settings.
	 */
	static void configureSessionCookie(ServletContext sc, AppConfig config) {
		SessionCookieConfig cookie = sc.getSessionCookieConfig();
		cookie.setName("TMONSESSION");
		cookie.setHttpOnly(true);
		cookie.setSecure(config.getBoolean("session.cookie-secure", true));
		cookie.setAttribute("SameSite", "Strict");
		sc.setSessionTimeout(config.getInt("session.timeout-minutes", 30));
	}
}
