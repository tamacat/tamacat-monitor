package org.tamacat.monitor.user;

/**
 * A host group permitted to an OPERATOR / VIEWER.
 *
 * @param serverId          Zabbix server
 * @param hostgroupId       Zabbix host group id (hstgrp.groupid)
 * @param hostgroupName     name at the time of assignment
 * @param includeSubgroups  also permit groups named "{hostgroupName}/..." (Q-01)
 */
public record UserScope(long serverId, long hostgroupId, String hostgroupName, boolean includeSubgroups) {
}
