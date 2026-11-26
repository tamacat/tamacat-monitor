package org.tamacat.monitor.zabbix.write;

import static org.tamacat.monitor.zabbix.write.HostGroupSets.ordered;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.tamacat.dao.Conditions;
import org.tamacat.dao.PreparedSql;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.zabbix.ZabbixVersion;

/**
 * Host writes (T-405), equal to what the Zabbix API writes (golden-master tests on 6.0 / 7.0):
 * <ul>
 * <li>create: hosts (other columns keep their DB defaults, as the API leaves them), hosts_groups,
 * one interface, host_tag; on 7.0 also the permission cache (see {@link HostGroupSets}).</li>
 * <li>update: only changed columns; groups and tags are changed by difference (delete removed, insert
 * added); on 7.0 the host is moved to the hgset of its new groups.</li>
 * </ul>
 * Not covered: SNMP interfaces, proxies, templates, inventory, macros, encryption settings.
 */
public final class HostWriter {

	/** Technical host name, as validated by Zabbix (ZBX_PREG_HOST_FORMAT). */
	private static final Pattern HOST = Pattern.compile("[0-9A-Za-z_. \\-]{1,128}");
	private static final Pattern IPV4 = Pattern.compile("((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)");
	private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
	private static final Pattern DNS = Pattern.compile("[0-9A-Za-z_.\\-]{1,255}");

	/** Interface types without extra tables: 1 agent, 3 IPMI, 4 JMX (2 SNMP is not supported). */
	public record InterfaceInput(int type, boolean useIp, String ip, String dns, String port) {
	}

	public record Tag(String tag, String value) {
	}

	/** Create: interface may be null (a host without interfaces). */
	public record HostInput(String host, String name, String description, int status, Set<Long> groupIds, List<Tag> tags,
			InterfaceInput iface) {
	}

	/**
	 * Update of an existing host. {@code expectedHost} / {@code expectedName} are the names the user saw
	 * (optimistic concurrency). A null field is left unchanged.
	 */
	public record HostUpdate(String expectedHost, String expectedName, String name, String description, Integer status,
			Set<Long> groupIds, List<Tag> tags, InterfaceInput mainInterface) {
	}

	public long create(WriteContext ctx, HostInput in) {
		String host = validHost(in.host());
		String name = validName(in.name(), host);
		int status = validStatus(in.status());
		Set<Long> groups = validGroups(ctx, in.groupIds());
		List<Tag> tags = validTags(in.tags());
		String description = validDescription(in.description());
		InterfaceInput iface = in.iface() == null ? null : validInterface(in.iface());
		requireUniqueHost(ctx, host, null);
		requireUniqueName(ctx, name, null);

		long hostId = ctx.nextId("hosts", "hostid");
		Map<String, Object> row = ordered("hostid", hostId, "host", host, "name", name, "name_upper",
				name.toUpperCase(Locale.ROOT), "status", status, "description", description, "flags", 0);
		row.putAll(ctx.schema().newHostColumns());
		ctx.insert("hosts", "hostid", row);
		if (ctx.schema().templateCache()) {
			ctx.insertWithoutId("host_template_cache", ordered("hostid", hostId, "link_hostid", hostId));
		}
		for (long g : groups) {
			ctx.insert("hosts_groups", "hostgroupid", ordered("hostgroupid", ctx.nextId("hosts_groups", "hostgroupid"),
					"hostid", hostId, "groupid", g));
		}
		if (iface != null) {
			insertInterface(ctx, hostId, iface);
		}
		for (Tag t : tags) {
			insertTag(ctx, hostId, t);
		}
		if (hasPermissionCache(ctx)) {
			ctx.insertWithoutId("host_hgset", ordered("hostid", hostId, "hgsetid", HostGroupSets.resolve(ctx, groups)));
		}
		return hostId;
	}

	public void update(WriteContext ctx, long hostId, HostUpdate u) {
		Current cur = load(ctx, hostId);
		if (!cur.host().equals(u.expectedHost()) || !cur.name().equals(u.expectedName())) {
			throw new ConflictException("error.write.conflict");
		}
		Map<String, Object> expected = new HashMap<>();
		Map<String, Object> changes = new HashMap<>();
		expected.put("host", u.expectedHost());
		expected.put("name", u.expectedName());
		if (u.name() != null) {
			String name = validName(u.name(), cur.host());
			if (!name.equals(cur.name())) {
				requireUniqueName(ctx, name, hostId);
				changes.put("name", name);
				changes.put("name_upper", name.toUpperCase(Locale.ROOT));
			}
		}
		if (u.description() != null) {
			String d = validDescription(u.description());
			if (!d.equals(cur.description())) {
				changes.put("description", d);
			}
		}
		if (u.status() != null && validStatus(u.status()) != cur.status()) {
			changes.put("status", u.status());
		}
		if (!changes.isEmpty()) {
			ctx.update("hosts", "hostid", hostId, expected, changes);
		}
		if (u.groupIds() != null) {
			updateGroups(ctx, hostId, validGroups(ctx, u.groupIds()));
		}
		if (u.tags() != null) {
			updateTags(ctx, hostId, validTags(u.tags()));
		}
		if (u.mainInterface() != null) {
			updateMainInterface(ctx, hostId, validInterface(u.mainInterface()));
		}
	}

	// ---- parts ----

	/**
	 * Deletes a host with its items, triggers and graphs (T-408), as CHost::deleteForce does. Refused when the
	 * host has discovery rules or web scenarios, is on a map, is used by an action (6.0 changes the action,
	 * 7.0 refuses), or is the last member of a maintenance; and when its triggers are used by actions or maps.
	 */
	public void delete(WriteContext ctx, long hostId, String expectedHost) {
		Current cur = load(ctx, hostId);
		if (!cur.host().equals(expectedHost)) {
			throw new ConflictException("error.write.conflict");
		}
		var db = ctx.db();
		String id = String.valueOf(hostId);
		if (count(ctx, "select count(*) from items where hostid = ? and flags <> 0", hostId) > 0) {
			throw new ConflictException("error.host.hasDiscovery");
		}
		if (count(ctx, "select count(*) from httptest where hostid = ?", hostId) > 0) {
			throw new ConflictException("error.host.hasWebScenarios");
		}
		if (count(ctx, "select count(*) from sysmaps_elements where elementtype = 0 and elementid = ?", hostId) > 0) {
			throw new ConflictException("error.host.inMap");
		}
		if (db.queryOne(PreparedSql.of("select count(*) from conditions where conditiontype = 1 and value = ?", id), r -> r.getInt(1))
				.orElse(0) > 0 || count(ctx, "select count(*) from opcommand_hst where hostid = ?", hostId) > 0) {
			throw new ConflictException("error.host.inAction");
		}
		if (count(ctx, "select count(*) from maintenances_hosts mh where mh.hostid = ? and not exists (select null from "
				+ "maintenances_hosts mh1 where mh1.maintenanceid = mh.maintenanceid and mh1.hostid <> mh.hostid) and not exists "
				+ "(select null from maintenances_groups mg where mg.maintenanceid = mh.maintenanceid)", hostId) > 0) {
			throw new ConflictException("error.host.lastInMaintenance");
		}
		List<Long> items = db.queryList(PreparedSql.of("select itemid from items where hostid = ? order by itemid", hostId),
				r -> r.getLong(1));
		Deletion.itemsBlocker(db, ctx.schema(), items, true).ifPresent(key -> {
			throw new ConflictException(key);
		});
		Deletion.deleteItems(ctx, items);
		Deletion.deleteRows(ctx, "host_tag", "hosttagid", PreparedSql.of("select hosttagid from host_tag where hostid = ?", hostId));
		Deletion.deleteRows(ctx, "hosts_groups", "hostgroupid",
				PreparedSql.of("select hostgroupid from hosts_groups where hostid = ?", hostId));
		Deletion.deleteRows(ctx, "interface", "interfaceid", PreparedSql.of("select interfaceid from interface where hostid = ?", hostId));
		Deletion.deleteRows(ctx, "hosts_templates", "hosttemplateid",
				PreparedSql.of("select hosttemplateid from hosts_templates where hostid = ?", hostId));
		Deletion.deleteRows(ctx, "host_inventory", "hostid", PreparedSql.of("select hostid from host_inventory where hostid = ?", hostId));
		if (hasPermissionCache(ctx)) {
			Deletion.deleteRows(ctx, "host_hgset", "hostid", PreparedSql.of("select hostid from host_hgset where hostid = ?", hostId));
		}
		ctx.delete("hosts", "hostid", hostId, Map.of("host", expectedHost));
	}

	private static int count(WriteContext ctx, String sql, long id) {
		return ctx.db().queryOne(PreparedSql.of(sql, id), r -> r.getInt(1)).orElse(0);
	}

	private record Current(String host, String name, String description, int status) {
	}

	private static Current load(WriteContext ctx, long hostId) {
		return ctx.db().queryOne(PreparedSql.of("select host, name, description, status from hosts "
				+ "where hostid = ? and status in (0, 1) and flags = 0", hostId),
				r -> new Current(r.getString("host"), r.getString("name"), r.getString("description"), r.getInt("status")))
				.orElseThrow(() -> new NotFoundException("host " + hostId));
	}

	private static boolean hasPermissionCache(WriteContext ctx) {
		return ctx.schema().version() != ZabbixVersion.V6_0;
	}

	private static void insertInterface(WriteContext ctx, long hostId, InterfaceInput i) {
		ctx.insert("interface", "interfaceid", ordered("interfaceid", ctx.nextId("interface", "interfaceid"), "hostid", hostId,
				"main", 1, "type", i.type(), "useip", i.useIp() ? 1 : 0, "ip", nz(i.ip()), "dns", nz(i.dns()), "port", i.port()));
	}

	private static void insertTag(WriteContext ctx, long hostId, Tag t) {
		ctx.insert("host_tag", "hosttagid", ordered("hosttagid", ctx.nextId("host_tag", "hosttagid"), "hostid", hostId,
				"tag", t.tag(), "value", t.value()));
	}

	private static void updateGroups(WriteContext ctx, long hostId, Set<Long> groups) {
		Map<Long, Long> current = new HashMap<>(); // groupid -> hostgroupid
		ctx.db().queryList(PreparedSql.of("select hostgroupid, groupid from hosts_groups where hostid = ?", hostId),
				r -> new long[] { r.getLong("groupid"), r.getLong("hostgroupid") }).forEach(a -> current.put(a[0], a[1]));
		if (current.keySet().equals(groups)) {
			return;
		}
		for (var e : current.entrySet()) {
			if (!groups.contains(e.getKey())) {
				ctx.delete("hosts_groups", "hostgroupid", e.getValue(), Map.of("groupid", e.getKey()));
			}
		}
		for (long g : groups) {
			if (!current.containsKey(g)) {
				ctx.insert("hosts_groups", "hostgroupid", ordered("hostgroupid", ctx.nextId("hosts_groups", "hostgroupid"),
						"hostid", hostId, "groupid", g));
			}
		}
		if (hasPermissionCache(ctx)) {
			long old = ctx.db().queryOne(PreparedSql.of("select hgsetid from host_hgset where hostid = ?", hostId),
					r -> r.getLong(1)).orElseThrow(() -> new IllegalStateException("host_hgset missing for " + hostId));
			long hgset = HostGroupSets.resolve(ctx, groups);
			if (hgset != old) {
				ctx.update("host_hgset", "hostid", hostId, Map.of("hgsetid", old), Map.of("hgsetid", hgset));
			}
		}
	}

	private static void updateTags(WriteContext ctx, long hostId, List<Tag> tags) {
		Map<Tag, Long> current = new HashMap<>();
		ctx.db().queryList(PreparedSql.of("select hosttagid, tag, value from host_tag where hostid = ?", hostId),
				r -> Map.entry(new Tag(r.getString("tag"), r.getString("value")), r.getLong("hosttagid")))
				.forEach(e -> current.put(e.getKey(), e.getValue()));
		for (var e : current.entrySet()) {
			if (!tags.contains(e.getKey())) {
				ctx.delete("host_tag", "hosttagid", e.getValue(), Map.of("tag", e.getKey().tag()));
			}
		}
		for (Tag t : tags) {
			if (!current.containsKey(t)) {
				insertTag(ctx, hostId, t);
			}
		}
	}

	private static void updateMainInterface(WriteContext ctx, long hostId, InterfaceInput i) {
		var row = ctx.db().queryOne(PreparedSql.of("select interfaceid, useip, ip, dns, port from interface "
				+ "where hostid = ? and type = ? and main = 1", hostId, i.type()),
				r -> new Object[] { r.getLong("interfaceid"), r.getInt("useip"), r.getString("ip"), r.getString("dns"),
						r.getString("port") });
		if (row.isEmpty()) {
			insertInterface(ctx, hostId, i);
			return;
		}
		Object[] cur = row.get();
		Map<String, Object> changes = new HashMap<>();
		Map<String, Object> expected = new HashMap<>();
		int useIp = i.useIp() ? 1 : 0;
		if (useIp != (int) cur[1]) {
			changes.put("useip", useIp);
			expected.put("useip", cur[1]);
		}
		if (!nz(i.ip()).equals(cur[2])) {
			changes.put("ip", nz(i.ip()));
			expected.put("ip", cur[2]);
		}
		if (!nz(i.dns()).equals(cur[3])) {
			changes.put("dns", nz(i.dns()));
			expected.put("dns", cur[3]);
		}
		if (!i.port().equals(cur[4])) {
			changes.put("port", i.port());
			expected.put("port", cur[4]);
		}
		if (!changes.isEmpty()) {
			ctx.update("interface", "interfaceid", (long) cur[0], expected, changes);
		}
	}

	// ---- validation ----

	static String validHost(String host) {
		String h = host == null ? "" : host.strip();
		if (!HOST.matcher(h).matches()) {
			throw ValidationException.of("host", "error.host.host");
		}
		return h;
	}

	static String validName(String name, String host) {
		String n = name == null || name.isBlank() ? host : name.strip();
		if (n.length() > 128) {
			throw ValidationException.of("name", "error.host.name");
		}
		return n;
	}

	private static String validDescription(String d) {
		String v = d == null ? "" : d;
		if (v.length() > 65535) {
			throw ValidationException.of("description", "error.host.description");
		}
		return v;
	}

	private static int validStatus(int status) {
		if (status != 0 && status != 1) {
			throw ValidationException.of("status", "error.host.status");
		}
		return status;
	}

	private static Set<Long> validGroups(WriteContext ctx, Set<Long> groupIds) {
		if (groupIds == null || groupIds.isEmpty()) {
			throw ValidationException.of("groups", "error.host.groups");
		}
		boolean v60 = ctx.schema().version() == ZabbixVersion.V6_0;
		int found = ctx.db().queryOne(SqlBuilder.of("select count(*) from hstgrp g")
				.where(Conditions.in("g.groupid", groupIds), Conditions.eq("g.flags", 0),
						v60 ? null : Conditions.eq("g.type", 0)).build(), r -> r.getInt(1)).orElse(0);
		if (found != groupIds.size()) {
			throw ValidationException.of("groups", "error.host.groups");
		}
		return new java.util.TreeSet<>(groupIds);
	}

	private static List<Tag> validTags(List<Tag> tags) {
		if (tags == null) {
			return List.of();
		}
		LinkedHashSet<Tag> out = new LinkedHashSet<>();
		for (Tag t : tags) {
			String tag = t.tag() == null ? "" : t.tag().strip();
			String value = t.value() == null ? "" : t.value();
			if (tag.isEmpty() || tag.length() > 255 || value.length() > 255) {
				throw ValidationException.of("tags", "error.host.tags");
			}
			out.add(new Tag(tag, value));
		}
		return new ArrayList<>(out);
	}

	static InterfaceInput validInterface(InterfaceInput i) {
		if (i.type() != 1 && i.type() != 3 && i.type() != 4) {
			throw ValidationException.of("interface", "error.host.interfaceType");
		}
		String ip = nz(i.ip()).strip();
		String dns = nz(i.dns()).strip();
		if (!ip.isEmpty() && !IPV4.matcher(ip).matches() && !(ip.contains(":") && IPV6.matcher(ip).matches())) {
			throw ValidationException.of("ip", "error.host.ip");
		}
		if (!dns.isEmpty() && !DNS.matcher(dns).matches()) {
			throw ValidationException.of("dns", "error.host.dns");
		}
		if (i.useIp() ? ip.isEmpty() : dns.isEmpty()) {
			throw ValidationException.of(i.useIp() ? "ip" : "dns", "error.required");
		}
		String port = nz(i.port()).strip();
		try {
			int p = Integer.parseInt(port);
			if (p < 0 || p > 65535) {
				throw new NumberFormatException();
			}
		} catch (NumberFormatException e) {
			throw ValidationException.of("port", "error.host.port");
		}
		return new InterfaceInput(i.type(), i.useIp(), ip, dns, port);
	}

	private static void requireUniqueHost(WriteContext ctx, String host, Long exceptId) {
		// host names are unique among hosts and templates (and proxies stored in hosts on 6.0)
		if (count(ctx, "select count(*) from hosts where host = ?", host, exceptId) > 0) {
			throw new ConflictException("error.host.duplicateHost");
		}
	}

	private static void requireUniqueName(WriteContext ctx, String name, Long exceptId) {
		if (count(ctx, "select count(*) from hosts where name = ? and status in (0, 1, 3) and flags in (0, 4)", name, exceptId) > 0) {
			throw new ConflictException("error.host.duplicateName");
		}
	}

	private static int count(WriteContext ctx, String sql, String value, Long exceptId) {
		PreparedSql q = exceptId == null ? PreparedSql.of(sql, value) : PreparedSql.of(sql + " and hostid <> ?", value, exceptId);
		return ctx.db().queryOne(q, r -> r.getInt(1)).orElse(0);
	}

	private static String nz(String s) {
		return Objects.requireNonNullElse(s, "");
	}
}
