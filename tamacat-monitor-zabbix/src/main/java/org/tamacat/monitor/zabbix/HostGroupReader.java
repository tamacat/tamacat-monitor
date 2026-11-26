package org.tamacat.monitor.zabbix;

import java.util.List;
import java.util.Optional;

import org.tamacat.dao.Conditions;
import org.tamacat.dao.Page;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.dao.Sort;
import org.tamacat.sql.LikeMode;
import org.tamacat.sql.SqlOperations;
import org.tamacat.monitor.zabbix.access.AccessScope;

/** Reads host groups (not template groups) from a Zabbix DB. */
public final class HostGroupReader {

	/** A host group with the number of hosts (monitored and not monitored) in it. */
	public record HostGroupSummary(long groupId, String name, int hostCount) {
	}

	private final ZabbixSchema schema;

	public HostGroupReader(ZabbixSchema schema) {
		this.schema = schema;
	}

	/**
	 * All host groups, unscoped (administrators assigning scopes).
	 *
	 * @param nameFilter case-insensitive substring, or null
	 */
	public List<HostGroup> find(SqlOperations db, String nameFilter, Page page) {
		var sql = SqlBuilder.of("select g.groupid, g.name from hstgrp g")
				.where(schema.hostGroupsOnly("g"), nameLike(nameFilter))
				.orderBy(Sort.asc("g.name"))
				.build(db.dialect(), page);
		return db.queryList(sql, r -> new HostGroup(r.getLong("groupid"), r.getString("name")));
	}

	/** One host group visible in {@code scope}, with its host count. */
	public Optional<HostGroupSummary> get(SqlOperations db, AccessScope scope, long groupId) {
		var sql = SqlBuilder.of(SELECT_WITH_COUNT)
				.where(schema.hostGroupsOnly("g"), scope.groupCondition("g.groupid"), Conditions.eq("g.groupid", groupId))
				.build();
		return db.queryOne(sql, r -> new HostGroupSummary(r.getLong("groupid"), r.getString("name"), r.getInt("host_count")));
	}

	/** Host groups visible in {@code scope}, with host counts. */
	public List<HostGroupSummary> search(SqlOperations db, AccessScope scope, String nameFilter, Page page) {
		var sql = SqlBuilder.of(SELECT_WITH_COUNT)
				.where(schema.hostGroupsOnly("g"), scope.groupCondition("g.groupid"), nameLike(nameFilter))
				.orderBy(Sort.asc("g.name"))
				.build(db.dialect(), page);
		return db.queryList(sql, r -> new HostGroupSummary(r.getLong("groupid"), r.getString("name"), r.getInt("host_count")));
	}

	private static final String SELECT_WITH_COUNT = "select g.groupid, g.name, (select count(*) from hosts_groups hg join hosts h "
			+ "on h.hostid = hg.hostid where hg.groupid = g.groupid and h.status in (0, 1) and h.flags in (0, 4)) "
			+ "as host_count from hstgrp g";

	private static org.tamacat.dao.Param nameLike(String nameFilter) {
		return nameFilter == null || nameFilter.isBlank() ? null
				: Conditions.likeIgnoreCase("g.name", nameFilter.strip(), LikeMode.CONTAINS);
	}
}
