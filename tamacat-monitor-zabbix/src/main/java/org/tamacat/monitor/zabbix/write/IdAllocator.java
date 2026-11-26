package org.tamacat.monitor.zabbix.write;

import org.tamacat.dao.PreparedSql;
import org.tamacat.dao.exception.DaoException;
import org.tamacat.sql.IdentifierRules;
import org.tamacat.sql.Session;

/**
 * Allocates ids the way Zabbix does (T-402), so that ids never collide with those created by the
 * Zabbix frontend or server at the same time.
 * <ul>
 * <li>{@code ids(table_name, field_name, nextid)} holds the <b>last</b> id handed out (observed on
 * 6.0.49 / 7.0.32: creating the first host group with max(groupid)=19 gave id 20 and nextid=20).</li>
 * <li>The row is locked with {@code SELECT ... FOR UPDATE} in the caller's transaction, so concurrent
 * allocations by Zabbix and tamacat-monitor are serialized until commit.</li>
 * <li>When no row exists yet, it is created from {@code max(field)}; a concurrent creator makes the
 * insert fail, and the allocation is retried with the locked row.</li>
 * </ul>
 * The retry relies on MySQL keeping the transaction usable after a failed statement. PostgreSQL aborts
 * the transaction instead; PostgreSQL support (T-502) needs a savepoint around the insert.
 */
public final class IdAllocator {

	/** The first id of {@code count} consecutive new ids. Must run inside a transaction. */
	public long reserve(Session tx, String table, String field, int count) {
		if (!tx.isInTransaction()) {
			throw new IllegalStateException("ids must be reserved inside a transaction");
		}
		if (count < 1 || count > 100_000) {
			throw new IllegalArgumentException("count");
		}
		String t = IdentifierRules.validate(table);
		String f = IdentifierRules.validate(field);
		for (int attempt = 0; attempt < 2; attempt++) {
			var last = tx.queryOne(PreparedSql.of("select nextid from ids where table_name = ? and field_name = ? for update", t, f),
					r -> r.getLong("nextid"));
			if (last.isPresent()) {
				tx.update(PreparedSql.of("update ids set nextid = ? where table_name = ? and field_name = ?",
						last.get() + count, t, f));
				return last.get() + 1;
			}
			long max = tx.queryOne(PreparedSql.of("select coalesce(max(" + f + "), 0) from " + t), r -> r.getLong(1)).orElse(0L);
			try {
				tx.update(PreparedSql.of("insert into ids (table_name, field_name, nextid) values (?, ?, ?)", t, f, max + count));
				return max + 1;
			} catch (DaoException e) {
				// another transaction created the row first: lock it and allocate from it
				if (attempt == 1) {
					throw e;
				}
			}
		}
		throw new IllegalStateException("unreachable");
	}
}
