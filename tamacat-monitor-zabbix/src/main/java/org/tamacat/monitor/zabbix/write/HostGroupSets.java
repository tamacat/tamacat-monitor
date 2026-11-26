package org.tamacat.monitor.zabbix.write;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.tamacat.dao.Conditions;
import org.tamacat.dao.SqlBuilder;

/**
 * Zabbix 7.0 permission cache of hosts (hgset / hgset_group / host_hgset / permission), maintained
 * as CHostGeneral::updateHgSets of the 7.0 frontend does:
 * <ul>
 * <li>hash = sha256 of the host's group ids, sorted numerically, joined by "|".</li>
 * <li>An existing set with the hash is reused (lowest hgsetid); otherwise a new set is created with its
 * groups and its permissions.</li>
 * <li>Permission per user group set: over the rights of its user groups on the set's groups, deny (0)
 * wins, otherwise the highest; deny is not stored.</li>
 * <li>Sets that are no longer used are left in place (as Zabbix does).</li>
 * </ul>
 * Not used on 6.0, which has no permission cache.
 */
final class HostGroupSets {

	static final int PERM_DENY = 0;

	private HostGroupSets() {
	}

	static String hash(Collection<Long> groupIds) {
		String joined = groupIds.stream().sorted().map(String::valueOf).collect(Collectors.joining("|"));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The hgsetid for these groups, creating the set (with groups and permissions) when missing. */
	static long resolve(WriteContext ctx, Collection<Long> groupIds) {
		String hash = hash(groupIds);
		Optional<Long> existing = ctx.db().queryOne(SqlBuilder.of("select min(hgsetid) from hgset")
				.where(Conditions.eq("hash", hash)).build(), r -> r.getNullableLong(1));
		if (existing.isPresent() && existing.get() != null) {
			return existing.get();
		}
		long hgsetId = ctx.nextId("hgset", "hgsetid");
		ctx.insert("hgset", "hgsetid", ordered("hgsetid", hgsetId, "hash", hash));
		for (long g : groupIds.stream().sorted().toList()) {
			ctx.insertWithoutId("hgset_group", ordered("hgsetid", hgsetId, "groupid", g));
		}
		for (var p : permissions(ctx, groupIds).entrySet()) {
			if (p.getValue() != PERM_DENY) {
				ctx.insertWithoutId("permission", ordered("ugsetid", p.getKey(), "hgsetid", hgsetId, "permission", p.getValue()));
			}
		}
		return hgsetId;
	}

	/** ugsetid -> permission for a new set of groups (deny wins, else highest). */
	private static Map<Long, Integer> permissions(WriteContext ctx, Collection<Long> groupIds) {
		Map<Long, Integer> result = new HashMap<>();
		var rows = ctx.db().queryList(SqlBuilder.of("select r.id, ugg.ugsetid, "
				+ "case when min(r.permission) = 0 then 0 else max(r.permission) end as permission "
				+ "from rights r join ugset_group ugg on ugg.usrgrpid = r.groupid")
				.where(Conditions.in("r.id", groupIds)).append("group by r.id, ugg.ugsetid").build(),
				r -> new long[] { r.getLong("ugsetid"), r.getInt("permission") });
		for (long[] row : rows) {
			long ugset = row[0];
			int perm = (int) row[1];
			Integer current = result.get(ugset);
			if (current == null || (current != PERM_DENY && (perm == PERM_DENY || perm > current))) {
				result.put(ugset, perm);
			}
		}
		return result;
	}

	static Map<String, Object> ordered(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}
}
