package org.tamacat.monitor.zabbix.write;

import java.util.Map;

/**
 * One row changed in the Zabbix DB, as shown in a preview (dry-run) and written to the audit log.
 *
 * @param table     table name
 * @param operation "insert", "update" or "delete"
 * @param id        primary key value
 * @param before    changed columns before (update / delete), empty for insert
 * @param after     changed columns after (insert / update), empty for delete
 */
public record Change(String table, String operation, long id, Map<String, String> before, Map<String, String> after) {

	public Change {
		before = Map.copyOf(before);
		after = Map.copyOf(after);
	}
}
