package org.tamacat.monitor.store.migration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Database;
import org.tamacat.sql.Session;

/**
 * Minimal schema migration for the central DB (no Flyway).
 * <ul>
 * <li>Scripts live in {@code db/migration/} and are listed, in order, in
 * {@code db/migration/migrations.list} (classpath scanning of a WAR is unreliable).</li>
 * <li>Applied versions are recorded in {@code schema_version} with a SHA-256 checksum;
 * a changed script that was already applied stops startup.</li>
 * <li>A named DB lock prevents two instances from migrating at the same time.</li>
 * </ul>
 * MySQL commits DDL implicitly, so a script that fails half way must be repaired by hand;
 * keep one logical change per script.
 */
public final class Migrator {

	private static final Logger LOG = LoggerFactory.getLogger(Migrator.class);
	private static final String LOCK_NAME = "tmon.schema_migration";

	private final Database db;
	private final ClassLoader loader;
	private final String location;
	private final Duration lockTimeout;

	public Migrator(Database db, ClassLoader loader) {
		this(db, loader, "db/migration", Duration.ofSeconds(60));
	}

	public Migrator(Database db, ClassLoader loader, String location, Duration lockTimeout) {
		this.db = db;
		this.loader = loader;
		this.location = location;
		this.lockTimeout = lockTimeout;
	}

	/** Loads the scripts listed in {@code migrations.list}. */
	public List<Migration> load() {
		List<Migration> list = new ArrayList<>();
		for (String file : readLines(location + "/migrations.list")) {
			list.add(Migration.of(file, readResource(location + "/" + file)));
		}
		list.sort(Comparator.comparingInt(Migration::version));
		for (int i = 1; i < list.size(); i++) {
			if (list.get(i).version() == list.get(i - 1).version()) {
				throw new IllegalStateException("Duplicate migration version: " + list.get(i).version());
			}
		}
		return list;
	}

	/** Applies pending migrations. @return the versions applied by this call */
	public List<Integer> migrate() {
		List<Migration> migrations = load();
		return db.withSession(s -> {
			acquireLock(s);
			try {
				return apply(s, migrations);
			} finally {
				s.queryOne(s.dialect().releaseLock(LOCK_NAME), r -> r.getInt(1));
			}
		});
	}

	private List<Integer> apply(Session s, List<Migration> migrations) {
		s.update(PreparedSql.of("create table if not exists schema_version ("
				+ "version int not null primary key, "
				+ "description varchar(200) not null, "
				+ "checksum char(64) not null, "
				+ "installed_at datetime(3) not null)"));
		Map<Integer, String> applied = new HashMap<>();
		for (var row : s.queryList(PreparedSql.of("select version, checksum from schema_version"),
				r -> Map.entry(r.getInt("version"), r.getString("checksum")))) {
			applied.put(row.getKey(), row.getValue());
		}
		List<Integer> done = new ArrayList<>();
		for (Migration m : migrations) {
			String checksum = applied.get(m.version());
			if (checksum != null) {
				if (!checksum.equals(m.checksum())) {
					throw new IllegalStateException("Migration V" + m.version()
							+ " was changed after it was applied (checksum mismatch). Add a new migration instead.");
				}
				continue;
			}
			LOG.info("Applying migration V{} {}", m.version(), m.description());
			for (String stmt : m.statements()) {
				s.update(new PreparedSql(stmt, List.of()));
			}
			s.update(PreparedSql.of("insert into schema_version (version, description, checksum, installed_at) values (?, ?, ?, ?)",
					m.version(), m.description(), m.checksum(), Instant.now()));
			done.add(m.version());
		}
		if (done.isEmpty()) {
			LOG.info("Schema is up to date (V{})", migrations.isEmpty() ? 0 : migrations.getLast().version());
		}
		return done;
	}

	private void acquireLock(Session s) {
		long deadline = System.nanoTime() + lockTimeout.toNanos();
		while (true) {
			int got = s.queryOne(s.dialect().tryLock(LOCK_NAME), r -> r.getInt(1)).orElse(0);
			if (got == 1) {
				return;
			}
			if (System.nanoTime() > deadline) {
				throw new IllegalStateException("Timed out waiting for the migration lock");
			}
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for the migration lock", e);
			}
		}
	}

	private List<String> readLines(String resource) {
		List<String> lines = new ArrayList<>();
		for (String line : readResource(resource).split("\n")) {
			String t = line.strip();
			if (!t.isEmpty() && !t.startsWith("#")) {
				lines.add(t);
			}
		}
		return lines;
	}

	private String readResource(String resource) {
		try (InputStream in = loader.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("Resource not found: " + resource);
			}
			try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				StringBuilder sb = new StringBuilder();
				r.lines().forEach(l -> sb.append(l).append('\n'));
				return sb.toString();
			}
		} catch (IOException e) {
			throw new IllegalStateException("Cannot read " + resource, e);
		}
	}
}
