package org.tamacat.monitor.zabbix;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tamacat.dao.Page;
import org.tamacat.sql.Database;
import org.tamacat.monitor.audit.AuditLog;
import org.tamacat.monitor.audit.AuditLog.Result;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.security.PasswordHasher;
import org.tamacat.monitor.security.SecretCipher;
import org.tamacat.monitor.server.ZabbixServer;
import org.tamacat.monitor.server.ZabbixServerRepository;
import org.tamacat.monitor.user.Actor;

/**
 * Registration of Zabbix servers, connection tests and version detection (T-104 / T-105).
 */
public final class ZabbixServerService {

	private static final Logger LOG = LoggerFactory.getLogger(ZabbixServerService.class);

	public static final String STATUS_OK = "OK";
	public static final String STATUS_UNSUPPORTED = "UNSUPPORTED";
	public static final String STATUS_ERROR = "ERROR";

	/**
	 * Settings entered by an administrator. On update, a null password keeps the stored one;
	 * {@code clearWritePassword} removes the write credentials.
	 */
	public record ServerInput(String name, String description, String dbUrl, String readUser, char[] readPassword,
			String writeUser, char[] writePassword, boolean clearWritePassword, boolean readOnly, boolean enabled) {
	}

	/** Result of a connection test. {@code messageKey} is set on failure. */
	public record TestResult(boolean connected, String versionLabel, Integer mandatory, boolean supported,
			String messageKey, String detail) {
	}

	private final Database central;
	private final ZabbixServerRepository repo = new ZabbixServerRepository();
	private final SecretCipher cipher;
	private final ZabbixDatabases pools;
	private final AuditLog audit;
	private final Clock clock;

	public ZabbixServerService(Database central, SecretCipher cipher, ZabbixDatabases pools, AuditLog audit,
			Clock clock) {
		this.central = central;
		this.cipher = cipher;
		this.pools = pools;
		this.audit = audit;
		this.clock = clock;
	}

	private static String readPurpose(long id) {
		return "zabbix_servers.db_read_password:" + id;
	}

	private static String writePurpose(long id) {
		return "zabbix_servers.db_write_password:" + id;
	}

	// ---- queries ----

	public List<ZabbixServer> findAll() {
		return repo.findAll(central);
	}

	public ZabbixServer get(long serverId) {
		return repo.findById(central, serverId).orElseThrow(() -> new NotFoundException("server " + serverId));
	}

	/** Writing to the Zabbix DB is possible: configured, write credentials present, supported version. */
	public static boolean writable(ZabbixServer s) {
		return s.enabled() && !s.readOnly() && s.writeUser() != null && s.hasWritePassword()
				&& STATUS_OK.equals(s.lastStatus()) && ZabbixVersion.fromLabel(s.zabbixVersion()).isPresent();
	}

	/** The read-only database of an enabled server with a supported version. */
	public Database database(ZabbixServer s) {
		if (!s.enabled()) {
			throw new ConflictException("error.server.disabled");
		}
		return pools.get(new ZabbixDatabases.Target(s.serverId(), s.name(), s.dbUrl(), s.readUser(),
				() -> readPassword(s.serverId())));
	}

	/**
	 * The database of the write account. Only for servers that are {@link #writable(ZabbixServer)}
	 * and whose version is supported for writing (6.0 / 7.0 / 8.0).
	 */
	public Database writeDatabase(ZabbixServer s) {
		if (!writable(s) || ZabbixVersion.fromLabel(s.zabbixVersion()).isEmpty()) {
			throw new ConflictException("error.write.readOnly");
		}
		return pools.get(new ZabbixDatabases.Target(s.serverId(), s.name(), s.dbUrl(), s.writeUser(),
				() -> writePassword(s.serverId()), true));
	}

	private String writePassword(long serverId) {
		byte[] enc = repo.findPasswords(central, serverId).map(ZabbixServerRepository.EncryptedPasswords::write)
				.orElseThrow(() -> new ConflictException("error.write.readOnly"));
		if (enc == null) {
			throw new ConflictException("error.write.readOnly");
		}
		return cipher.decrypt(enc, writePurpose(serverId));
	}

	public ZabbixSchema schema(ZabbixServer s) {
		return ZabbixSchema.of(ZabbixVersion.fromLabel(s.zabbixVersion())
				.orElseThrow(() -> new ConflictException("error.server.unsupported")));
	}

	public List<HostGroup> hostGroups(long serverId, String filter, Page page) {
		ZabbixServer s = get(serverId);
		return new HostGroupReader(schema(s)).find(database(s), filter, page);
	}

	private String readPassword(long serverId) {
		byte[] enc = repo.findPasswords(central, serverId).map(ZabbixServerRepository.EncryptedPasswords::read)
				.orElseThrow(() -> new NotFoundException("server " + serverId));
		return cipher.decrypt(enc, readPurpose(serverId));
	}

	// ---- changes ----

	public ZabbixServer create(ServerInput in, Actor actor) {
		try {
			validate(in, true);
			Instant now = clock.instant();
			long id = central.inTransaction(tx -> {
				if (repo.findByName(tx, in.name().strip()).isPresent()) {
					throw new ConflictException("error.server.duplicate");
				}
				long newId = repo.insert(tx, in.name().strip(), in.description(), in.dbUrl().strip(),
						in.readUser().strip(), in.writeUser(), in.readOnly(), in.enabled(), now);
				repo.updateReadPassword(tx, newId, cipher.encrypt(new String(in.readPassword()), readPurpose(newId)));
				if (in.writePassword() != null && in.writePassword().length > 0) {
					repo.updateWritePassword(tx, newId,
							cipher.encrypt(new String(in.writePassword()), writePurpose(newId)));
				}
				audit.record(tx, actor, newId, "zabbix_server", in.name(), "create", Result.SUCCESS,
						Map.of("dbUrl", in.dbUrl(), "readOnly", in.readOnly(), "enabled", in.enabled()));
				return newId;
			});
			return check(id);
		} finally {
			wipe(in);
		}
	}

	public ZabbixServer update(long serverId, ServerInput in, Actor actor) {
		try {
			validate(in, false);
			Instant now = clock.instant();
			central.inTransaction(tx -> {
				ZabbixServer before = repo.findById(tx, serverId)
						.orElseThrow(() -> new NotFoundException("server " + serverId));
				repo.findByName(tx, in.name().strip()).filter(o -> o.serverId() != serverId).ifPresent(o -> {
					throw new ConflictException("error.server.duplicate");
				});
				repo.update(tx, serverId, in.name().strip(), in.description(), in.dbUrl().strip(), in.readUser().strip(),
						in.clearWritePassword() ? null : in.writeUser(), in.readOnly(), in.enabled(), now);
				if (in.readPassword() != null && in.readPassword().length > 0) {
					repo.updateReadPassword(tx, serverId,
							cipher.encrypt(new String(in.readPassword()), readPurpose(serverId)));
				}
				if (in.clearWritePassword()) {
					repo.updateWritePassword(tx, serverId, null);
				} else if (in.writePassword() != null && in.writePassword().length > 0) {
					repo.updateWritePassword(tx, serverId,
							cipher.encrypt(new String(in.writePassword()), writePurpose(serverId)));
				}
				audit.record(tx, actor, serverId, "zabbix_server", before.name(), "update", Result.SUCCESS,
						Map.of("dbUrl", in.dbUrl(), "readOnly", in.readOnly(), "enabled", in.enabled(),
								"readPasswordChanged", in.readPassword() != null && in.readPassword().length > 0));
				return null;
			});
			pools.invalidate(serverId);
			return check(serverId);
		} finally {
			wipe(in);
		}
	}

	public void delete(long serverId, Actor actor) {
		central.inTransaction(tx -> {
			ZabbixServer s = repo.findById(tx, serverId).orElseThrow(() -> new NotFoundException("server " + serverId));
			repo.delete(tx, serverId);
			audit.record(tx, actor, serverId, "zabbix_server", s.name(), "delete", Result.SUCCESS, null);
			return null;
		});
		pools.invalidate(serverId);
	}

	/** Connects with the stored settings, detects the version and stores the status. */
	public ZabbixServer check(long serverId) {
		ZabbixServer s = get(serverId);
		TestResult r = test(s.dbUrl(), s.readUser(), readPassword(s.serverId()));
		String status = !r.connected() ? STATUS_ERROR : r.supported() ? STATUS_OK : STATUS_UNSUPPORTED;
		repo.updateStatus(central, serverId, r.versionLabel(), r.mandatory(), status, clock.instant());
		if (!STATUS_OK.equals(status)) {
			pools.invalidate(serverId);
		}
		return get(serverId);
	}

	/** Tests settings that are not saved yet. */
	public TestResult test(String url, String user, char[] password) {
		try {
			JdbcUrlPolicy.validate(url, "dbUrl");
			return test(url, user, password == null ? "" : new String(password));
		} finally {
			PasswordHasher.wipe(password);
		}
	}

	/** Tests a saved server, optionally with a new (unsaved) password. */
	public TestResult testSaved(long serverId, String url, String user, char[] newPassword) {
		if (newPassword != null && newPassword.length > 0) {
			return test(url, user, newPassword);
		}
		JdbcUrlPolicy.validate(url, "dbUrl");
		return test(url, user, readPassword(serverId));
	}

	private TestResult test(String url, String user, String password) {
		try {
			return ZabbixDatabases.withSingleConnection(url, user, password, db -> {
				ZabbixVersionDetector.DetectedVersion v = ZabbixVersionDetector.detect(db);
				return new TestResult(true, v.label(), v.mandatory(), v.supported(),
						v.supported() ? null : "error.server.unsupported", null);
			});
		} catch (RuntimeException e) {
			LOG.info("Zabbix DB connection test failed: {}", rootMessage(e));
			return new TestResult(false, null, null, false, "error.server.connect", rootMessage(e));
		}
	}

	private static String rootMessage(Throwable t) {
		Throwable c = t;
		while (c.getCause() != null) {
			c = c.getCause();
		}
		String m = c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
		// first line only; driver messages can be long
		int nl = m.indexOf('\n');
		return nl > 0 ? m.substring(0, nl) : m;
	}

	private static void validate(ServerInput in, boolean creating) {
		ValidationException.Collector v = new ValidationException.Collector();
		v.check(in.name() != null && !in.name().isBlank() && in.name().strip().length() <= 100, "name", "error.server.name");
		v.check(in.description() == null || in.description().length() <= 500, "description", "error.server.description");
		v.check(in.readUser() != null && !in.readUser().isBlank() && in.readUser().length() <= 100, "readUser",
				"error.server.user");
		if (creating) {
			v.check(in.readPassword() != null && in.readPassword().length > 0, "readPassword", "error.required");
		}
		v.check(in.writeUser() == null || in.writeUser().length() <= 100, "writeUser", "error.server.user");
		v.throwIfAny();
		JdbcUrlPolicy.validate(in.dbUrl(), "dbUrl");
	}

	private static void wipe(ServerInput in) {
		PasswordHasher.wipe(in.readPassword());
		PasswordHasher.wipe(in.writePassword());
	}

	public Optional<ZabbixServer> find(long serverId) {
		return repo.findById(central, serverId);
	}
}
