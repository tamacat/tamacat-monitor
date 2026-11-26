package org.tamacat.monitor.zabbix.write;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.tamacat.dao.PreparedSql;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.zabbix.ZabbixVersion;

/**
 * Host group writes (T-404), equal to what the Zabbix API writes (checked by golden-master tests):
 * <ul>
 * <li>create: one {@code hstgrp} row (6.0: internal=0, flags=0; 7.0: flags=0, type=0) with a random
 * uuid; id from {@code ids}.</li>
 * <li>rename: {@code hstgrp.name} only (children are not renamed, as in hostgroup.update).</li>
 * <li>delete: only an empty, unreferenced, non-internal, non-discovered group. Groups with hosts or
 * referenced by actions, scripts, maintenances, correlations, discovery or host prototypes must be
 * deleted in the Zabbix frontend, which also updates those objects.</li>
 * </ul>
 */
public final class HostGroupWriter {

	static final int MAX_NAME = 255;

	public long create(WriteContext ctx, String name) {
		String n = validName(name);
		requireUnique(ctx, n, null);
		long id = ctx.nextId("hstgrp", "groupid");
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("groupid", id);
		row.put("name", n);
		row.put("flags", 0);
		row.put("uuid", uuid());
		if (ctx.schema().version() == ZabbixVersion.V6_0) {
			row.put("internal", 0);
		} else {
			row.put("type", 0);
		}
		ctx.insert("hstgrp", "groupid", row);
		return id;
	}

	public void rename(WriteContext ctx, long groupId, String expectedName, String newName) {
		String n = validName(newName);
		Group g = load(ctx, groupId);
		if (g.flags() != 0) {
			throw new ConflictException("error.hostgroup.discovered");
		}
		if (n.equals(g.name())) {
			return;
		}
		requireUnique(ctx, n, groupId);
		ctx.update("hstgrp", "groupid", groupId, Map.of("name", expectedName), Map.of("name", n));
	}

	public void delete(WriteContext ctx, long groupId, String expectedName) {
		Group g = load(ctx, groupId);
		if (g.flags() != 0) {
			throw new ConflictException("error.hostgroup.discovered");
		}
		if (g.internal()) {
			throw new ConflictException("error.hostgroup.internal");
		}
		for (String check : references(ctx.schema().version())) {
			// conditions.value is text: bind the id as a string there
			Object id = check.contains("conditions") ? String.valueOf(groupId) : (Object) groupId;
			if (ctx.db().queryOne(PreparedSql.of(check, id), r -> r.getInt(1)).orElse(0) > 0) {
				throw new ConflictException("error.hostgroup.inUse");
			}
		}
		ctx.delete("hstgrp", "groupid", groupId, Map.of("name", expectedName));
	}

	/** Queries counting references that block a delete (one placeholder: the group id). */
	static String[] references(ZabbixVersion v) {
		var list = new java.util.ArrayList<>(java.util.List.of(
				"select count(*) from hosts_groups where groupid = ?",
				org.tamacat.monitor.zabbix.ZabbixSchema.of(v).discoveryGroupReference(),
				"select count(*) from corr_condition_group where groupid = ?",
				"select count(*) from group_prototype where groupid = ?",
				"select count(*) from group_discovery where groupid = ?",
				"select count(*) from opcommand_grp where groupid = ?",
				"select count(*) from opgroup where groupid = ?",
				"select count(*) from scripts where groupid = ?",
				"select count(*) from maintenances_groups where groupid = ?",
				// action conditions "host group = x" (conditiontype 0) store the id as text
				"select count(*) from conditions where conditiontype = 0 and value = ?"));
		if (v != ZabbixVersion.V6_0) {
			list.add("select count(*) from hgset_group where groupid = ?");
		}
		return list.toArray(String[]::new);
	}

	private record Group(String name, int flags, boolean internal) {
	}

	private static Group load(WriteContext ctx, long groupId) {
		boolean v60 = ctx.schema().version() == ZabbixVersion.V6_0;
		String sql = "select name, flags, " + (v60 ? "internal" : "0 as internal") + " from hstgrp where groupid = ?"
				+ (v60 ? "" : " and type = 0");
		return ctx.db().queryOne(PreparedSql.of(sql, groupId),
				r -> new Group(r.getString("name"), r.getInt("flags"), r.getInt("internal") != 0))
				.orElseThrow(() -> new NotFoundException("host group " + groupId));
	}

	private static void requireUnique(WriteContext ctx, String name, Long exceptId) {
		// 6.0: host and template groups share the name space; 7.0: unique per type
		String sql = "select count(*) from hstgrp where name = ?" + (ctx.schema().version() == ZabbixVersion.V6_0 ? "" : " and type = 0")
				+ (exceptId == null ? "" : " and groupid <> ?");
		PreparedSql q = exceptId == null ? PreparedSql.of(sql, name) : PreparedSql.of(sql, name, exceptId);
		if (ctx.db().queryOne(q, r -> r.getInt(1)).orElse(0) > 0) {
			throw new ConflictException("error.hostgroup.duplicate");
		}
	}

	static String validName(String name) {
		String n = name == null ? "" : name.strip();
		if (n.isEmpty() || n.length() > MAX_NAME || n.startsWith("/") || n.endsWith("/") || n.contains("//")) {
			throw ValidationException.of("name", "error.hostgroup.name");
		}
		return n;
	}

	/** 32 hex characters, as Zabbix stores uuids. */
	static String uuid() {
		return UUID.randomUUID().toString().replace("-", "");
	}
}
