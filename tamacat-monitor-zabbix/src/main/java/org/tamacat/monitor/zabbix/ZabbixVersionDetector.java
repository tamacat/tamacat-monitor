package org.tamacat.monitor.zabbix;

import java.util.Optional;

import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.SqlOperations;

/** Reads {@code dbversion} of a Zabbix DB. */
public final class ZabbixVersionDetector {

	/**
	 * @param mandatory {@code dbversion.mandatory}
	 * @param optional  {@code dbversion.optional}
	 * @param version   the supported version, or empty when unsupported
	 */
	public record DetectedVersion(int mandatory, int optional, Optional<ZabbixVersion> version) {
		public boolean supported() {
			return version.isPresent();
		}

		public String label() {
			return ZabbixVersion.labelOf(mandatory);
		}
	}

	private ZabbixVersionDetector() {
	}

	public static DetectedVersion detect(SqlOperations db) {
		return db.queryOne(PreparedSql.of("select mandatory, optional from dbversion"),
				r -> new DetectedVersion(r.getInt("mandatory"), r.getInt("optional"),
						ZabbixVersion.fromDbVersion(r.getInt("mandatory"))))
				.orElseThrow(() -> new IllegalStateException("dbversion is empty: not a Zabbix database?"));
	}
}
