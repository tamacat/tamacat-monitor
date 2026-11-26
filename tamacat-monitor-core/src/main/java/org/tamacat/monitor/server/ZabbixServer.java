package org.tamacat.monitor.server;

import java.time.Instant;

/**
 * A registered Zabbix server (passwords are not part of this record).
 *
 * @param zabbixVersion       detected "major.minor", or null before the first check
 * @param dbVersionMandatory  dbversion.mandatory, or null
 * @param readOnly            true when writing is not allowed (configured, no write user, or unsupported version)
 * @param lastStatus          "OK", "UNSUPPORTED" or "ERROR"
 */
public record ZabbixServer(
		long serverId,
		String name,
		String description,
		String dbUrl,
		String readUser,
		String writeUser,
		boolean hasWritePassword,
		String zabbixVersion,
		Integer dbVersionMandatory,
		boolean readOnly,
		boolean enabled,
		String lastStatus,
		Instant lastCheckedAt,
		Instant createdAt,
		Instant updatedAt) {
}
