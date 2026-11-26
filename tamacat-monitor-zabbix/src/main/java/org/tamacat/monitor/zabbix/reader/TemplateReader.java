package org.tamacat.monitor.zabbix.reader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.tamacat.dao.Conditions;
import org.tamacat.dao.Page;
import org.tamacat.dao.Param;
import org.tamacat.dao.PreparedSql;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.dao.Sort;
import org.tamacat.sql.LikeMode;
import org.tamacat.sql.SqlOperations;

/**
 * Templates for linking (T-601). Templates are global in Zabbix; the list shows whether tamacat-monitor can link
 * each one (see TemplateLinker): the reason is null when it can, else a message key. Triggers or graphs
 * that use items of other hosts are only detected when linking.
 */
public final class TemplateReader {

	public static final int MAX_PAGE = 200;

	public record TemplateSummary(long templateId, String host, String name, int itemCount, String reason) {
	}

	public record LinkedTemplate(long templateId, String host, String name) {
	}

	public List<TemplateSummary> search(SqlOperations db, String text, Page page) {
		if (page.limit() > MAX_PAGE) {
			throw new IllegalArgumentException("page too large");
		}
		List<Param> where = new ArrayList<>();
		where.add(Param.of("t.status = 3"));
		if (text != null && !text.isBlank()) {
			where.add(Conditions.likeIgnoreCase("t.name", text.strip(), LikeMode.CONTAINS)
					.or(Conditions.likeIgnoreCase("t.host", text.strip(), LikeMode.CONTAINS)));
		}
		record Row(long id, String host, String name, int items) {
		}
		List<Row> rows = db.queryList(SqlBuilder.of("select t.hostid, t.host, t.name, "
				+ "(select count(*) from items i where i.hostid = t.hostid and i.flags = 0) as item_count from hosts t")
				.where(where).orderBy(Sort.asc("t.name")).build(db.dialect(), page),
				r -> new Row(r.getLong("hostid"), r.getString("host"), r.getString("name"), r.getInt("item_count")));
		if (rows.isEmpty()) {
			return List.of();
		}
		List<Long> ids = rows.stream().map(Row::id).toList();
		Set<Long> discovery = set(db, "select distinct hostid from items", ids, "hostid", Param.of("flags <> 0"));
		Set<Long> web = set(db, "select distinct hostid from httptest", ids, "hostid", null);
		Set<Long> nested = set(db, "select distinct hostid from hosts_templates", ids, "hostid", null);
		Set<Long> types = set(db, "select distinct hostid from items", ids, "hostid",
				Param.of("(type not in (0, 2, 7) or valuemapid is not null or master_itemid is not null)"));
		return rows.stream().map(r -> new TemplateSummary(r.id(), r.host(), r.name(), r.items(),
				discovery.contains(r.id()) ? "error.template.discovery"
						: web.contains(r.id()) ? "error.template.webScenarios"
								: nested.contains(r.id()) ? "error.template.nested"
										: types.contains(r.id()) ? "error.template.itemTypes" : null))
				.toList();
	}

	public List<LinkedTemplate> linkedTo(SqlOperations db, long hostId) {
		return db.queryList(PreparedSql.of("select t.hostid, t.host, t.name from hosts_templates ht join hosts t on t.hostid = ht.templateid "
				+ "where ht.hostid = ? order by t.name", hostId),
				r -> new LinkedTemplate(r.getLong("hostid"), r.getString("host"), r.getString("name")));
	}

	private static Set<Long> set(SqlOperations db, String select, List<Long> ids, String column, Param extra) {
		List<Param> where = new ArrayList<>();
		where.add(Conditions.in(column, ids));
		if (extra != null) {
			where.add(extra);
		}
		return new HashSet<>(db.queryList(SqlBuilder.of(select).where(where).build(), r -> r.getLong(1)));
	}
}
