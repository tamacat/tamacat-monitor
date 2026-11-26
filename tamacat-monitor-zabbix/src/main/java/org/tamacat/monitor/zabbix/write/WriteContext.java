package org.tamacat.monitor.zabbix.write;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.tamacat.dao.BindValue;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.IdentifierRules;
import org.tamacat.sql.Session;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.server.ZabbixServer;
import org.tamacat.monitor.zabbix.ZabbixSchema;

/**
 * Write operations inside one transaction on a Zabbix DB (T-403).
 * <ul>
 * <li>Every change goes through {@link #insert}, {@link #update} or {@link #delete}, which record it
 * for the preview and the audit log.</li>
 * <li>{@link #update} and {@link #delete} are conditional on the values the user saw (optimistic
 * concurrency): when another user or the Zabbix frontend changed the row meanwhile, nothing is
 * changed and {@code error.write.conflict} is raised.</li>
 * </ul>
 */
public final class WriteContext {

	private final Session tx;
	private final ZabbixServer server;
	private final ZabbixSchema schema;
	private final IdAllocator ids;
	private final List<Change> changes = new ArrayList<>();

	WriteContext(Session tx, ZabbixServer server, ZabbixSchema schema, IdAllocator ids) {
		this.tx = tx;
		this.server = server;
		this.schema = schema;
		this.ids = ids;
	}

	public Session db() {
		return tx;
	}

	public ZabbixServer server() {
		return server;
	}

	public ZabbixSchema schema() {
		return schema;
	}

	public List<Change> changes() {
		return List.copyOf(changes);
	}

	/** Next id of {@code table.field} (Zabbix protocol). */
	public long nextId(String table, String field) {
		return ids.reserve(tx, table, field, 1);
	}

	/** Inserts a row of a link table without a single id column (recorded with id 0). */
	public void insertWithoutId(String table, Map<String, Object> values) {
		insertRow(table, values);
		changes.add(new Change(IdentifierRules.validate(table), "insert", 0, Map.of(), text(values)));
	}

	/** Inserts a row; {@code values} must contain the primary key {@code idColumn}. */
	public void insert(String table, String idColumn, Map<String, Object> values) {
		insertRow(table, values);
		changes.add(new Change(IdentifierRules.validate(table), "insert", ((Number) values.get(idColumn)).longValue(), Map.of(),
				text(values)));
	}

	private void insertRow(String table, Map<String, Object> values) {
		String t = IdentifierRules.validate(table);
		StringJoiner cols = new StringJoiner(", ");
		StringJoiner marks = new StringJoiner(", ");
		List<BindValue> binds = new ArrayList<>();
		for (var e : values.entrySet()) {
			cols.add(IdentifierRules.validate(e.getKey()));
			marks.add("?");
			binds.add(BindValue.from(e.getValue()));
		}
		tx.update(new PreparedSql("insert into " + t + " (" + cols + ") values (" + marks + ")", binds));
	}

	/**
	 * Updates columns of one row if its current values equal {@code expected}.
	 *
	 * @throws ConflictException error.write.conflict when the row is gone or was changed meanwhile
	 */
	public void update(String table, String idColumn, long id, Map<String, Object> expected, Map<String, Object> changes) {
		String t = IdentifierRules.validate(table);
		StringJoiner set = new StringJoiner(", ");
		List<BindValue> binds = new ArrayList<>();
		for (var e : changes.entrySet()) {
			set.add(IdentifierRules.validate(e.getKey()) + " = ?");
			binds.add(BindValue.from(e.getValue()));
		}
		StringBuilder where = new StringBuilder(IdentifierRules.validate(idColumn) + " = ?");
		binds.add(BindValue.of(id));
		for (var e : expected.entrySet()) {
			where.append(" and ").append(IdentifierRules.validate(e.getKey())).append(" = ?");
			binds.add(BindValue.from(e.getValue()));
		}
		int n = tx.update(new PreparedSql("update " + t + " set " + set + " where " + where, binds));
		if (n != 1) {
			throw new ConflictException("error.write.conflict");
		}
		this.changes.add(new Change(t, "update", id, text(expected), text(changes)));
	}

	/**
	 * Deletes one row if its current values equal {@code expected}.
	 *
	 * @throws ConflictException error.write.conflict when the row is gone or was changed meanwhile
	 */
	public void delete(String table, String idColumn, long id, Map<String, Object> expected) {
		String t = IdentifierRules.validate(table);
		List<BindValue> binds = new ArrayList<>();
		StringBuilder where = new StringBuilder(IdentifierRules.validate(idColumn) + " = ?");
		binds.add(BindValue.of(id));
		for (var e : expected.entrySet()) {
			where.append(" and ").append(IdentifierRules.validate(e.getKey())).append(" = ?");
			binds.add(BindValue.from(e.getValue()));
		}
		int n = tx.update(new PreparedSql("delete from " + t + " where " + where, binds));
		if (n != 1) {
			throw new ConflictException("error.write.conflict");
		}
		changes.add(new Change(t, "delete", id, text(expected), Map.of()));
	}

	private static Map<String, String> text(Map<String, Object> values) {
		Map<String, String> m = new LinkedHashMap<>();
		values.forEach((k, v) -> m.put(k, String.valueOf(v instanceof BindValue b ? b.value() : v)));
		return m;
	}
}
