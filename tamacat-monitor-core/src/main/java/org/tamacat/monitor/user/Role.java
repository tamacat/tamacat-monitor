package org.tamacat.monitor.user;

import java.util.Optional;

/** Roles (DESIGN.md 6.2). */
public enum Role {
	/** all servers, user management, server registration */
	SYSTEM_ADMIN,
	/** all settings of assigned Zabbix servers, action editing */
	SERVER_ADMIN,
	/** edit hosts and items in permitted host groups */
	OPERATOR,
	/** read only in permitted host groups */
	VIEWER;

	public static Optional<Role> parse(String s) {
		for (Role r : values()) {
			if (r.name().equalsIgnoreCase(s)) {
				return Optional.of(r);
			}
		}
		return Optional.empty();
	}
}
