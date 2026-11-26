package org.tamacat.monitor.zabbix.access;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.tamacat.dao.BindValue;
import org.tamacat.dao.Param;
import org.tamacat.sql.IdentifierRules;

/**
 * What a user may read on one Zabbix server (T-202). Every reader takes a scope and adds its
 * condition to the SQL, so the restriction is enforced in the data access layer, not in the UI.
 */
public sealed interface AccessScope permits AccessScope.All, AccessScope.Groups, AccessScope.None {

	/** Upper bound of host groups in one scope (IN list size). */
	int MAX_GROUPS = 5000;

	/** Everything on the server (SYSTEM_ADMIN, assigned SERVER_ADMIN). */
	record All() implements AccessScope {
	}

	/** Hosts in these host groups only (OPERATOR / VIEWER). */
	record Groups(Set<Long> groupIds) implements AccessScope {
		public Groups {
			groupIds = Set.copyOf(groupIds);
			if (groupIds.isEmpty() || groupIds.size() > MAX_GROUPS) {
				throw new IllegalArgumentException("1.." + MAX_GROUPS + " groups required");
			}
		}
	}

	/** Nothing. */
	record None() implements AccessScope {
	}

	AccessScope ALL = new All();
	AccessScope NONE = new None();

	static AccessScope of(Set<Long> groupIds) {
		return groupIds.isEmpty() ? NONE : new Groups(groupIds);
	}

	default boolean isAll() {
		return this instanceof All;
	}

	/** Condition on a host id column: the host belongs to a permitted group. Null for All. */
	default Param hostCondition(String hostIdColumn) {
		String col = IdentifierRules.validate(hostIdColumn);
		return switch (this) {
			case All a -> null;
			case None n -> Param.of("1=0");
			case Groups g -> inList("exists (select 1 from hosts_groups sg where sg.hostid = " + col
					+ " and sg.groupid in (", g.groupIds(), "))");
		};
	}

	/** Condition on a host group id column. Null for All. */
	default Param groupCondition(String groupIdColumn) {
		String col = IdentifierRules.validate(groupIdColumn);
		return switch (this) {
			case All a -> null;
			case None n -> Param.of("1=0");
			case Groups g -> inList(col + " in (", g.groupIds(), ")");
		};
	}

	/**
	 * Condition on a trigger id column: at least one host of the trigger belongs to a permitted
	 * group. Null for All.
	 */
	default Param triggerCondition(String triggerIdColumn) {
		String col = IdentifierRules.validate(triggerIdColumn);
		return switch (this) {
			case All a -> null;
			case None n -> Param.of("1=0");
			case Groups g -> inList("exists (select 1 from functions sf join items si on si.itemid = sf.itemid "
					+ "join hosts_groups sg on sg.hostid = si.hostid where sf.triggerid = " + col
					+ " and sg.groupid in (", g.groupIds(), "))");
		};
	}

	private static Param inList(String prefix, Set<Long> ids, String suffix) {
		List<BindValue> values = new ArrayList<>(ids.size());
		StringBuilder sb = new StringBuilder(prefix);
		for (long id : ids.stream().sorted().toList()) {
			if (!values.isEmpty()) {
				sb.append(',');
			}
			sb.append('?');
			values.add(BindValue.of(id));
		}
		return new Param(sb.append(suffix).toString(), values);
	}
}
