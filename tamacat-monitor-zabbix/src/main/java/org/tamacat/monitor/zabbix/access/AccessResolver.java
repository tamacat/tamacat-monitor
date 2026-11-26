package org.tamacat.monitor.zabbix.access;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.tamacat.dao.Conditions;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.sql.LikeMode;
import org.tamacat.sql.SqlOperations;
import org.tamacat.monitor.user.Role;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.user.UserScope;

/**
 * Computes a user's {@link AccessScope} on a Zabbix server.
 * <ul>
 * <li>SYSTEM_ADMIN: everything on every server.</li>
 * <li>SERVER_ADMIN: everything on the assigned servers.</li>
 * <li>Otherwise: the permitted host groups of that server; a scope with "include subgroups"
 * also permits groups named {@code <name>/...} (resolved by the current group name in Zabbix).</li>
 * </ul>
 */
public final class AccessResolver {

	/** The user's assignments, loaded once per request. */
	public record Assignments(Set<Long> adminServerIds, List<UserScope> scopes) {
		public Assignments {
			adminServerIds = Set.copyOf(adminServerIds);
			scopes = List.copyOf(scopes);
		}
	}

	private AccessResolver() {
	}

	/** Full control of the server (all settings, actions). */
	public static boolean isServerAdmin(User user, Assignments a, long serverId) {
		return user.isSystemAdmin() || (user.has(Role.SERVER_ADMIN) && a.adminServerIds().contains(serverId));
	}

	/** The user can see something on the server. */
	public static boolean canAccess(User user, Assignments a, long serverId) {
		return isServerAdmin(user, a, serverId) || a.scopes().stream().anyMatch(s -> s.serverId() == serverId);
	}

	/**
	 * @param zabbix the Zabbix DB of the server (used only for subgroups)
	 */
	public static AccessScope resolve(User user, Assignments a, long serverId, SqlOperations zabbix) {
		if (isServerAdmin(user, a, serverId)) {
			return AccessScope.ALL;
		}
		Set<Long> ids = new HashSet<>();
		for (UserScope s : a.scopes()) {
			if (s.serverId() != serverId) {
				continue;
			}
			ids.add(s.hostgroupId());
			if (s.includeSubgroups()) {
				ids.addAll(subgroups(zabbix, s.hostgroupId()));
			}
		}
		return AccessScope.of(ids);
	}

	/** Groups whose name starts with "{current name of the group}/". */
	static List<Long> subgroups(SqlOperations zabbix, long groupId) {
		String name = zabbix.queryOne(SqlBuilder.of("select name from hstgrp").where(Conditions.eq("groupid", groupId)).build(),
				r -> r.getString("name")).orElse(null);
		if (name == null) {
			return List.of();
		}
		return zabbix.queryList(SqlBuilder.of("select groupid from hstgrp")
				.where(Conditions.like("name", name + "/", LikeMode.PREFIX)).build(), r -> r.getLong("groupid"));
	}
}
