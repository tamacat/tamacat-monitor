package org.tamacat.monitor.zabbix;

import java.util.Optional;

/**
 * Supported Zabbix versions, identified from {@code dbversion.mandatory}
 * (e.g. 6000000 for 6.0, 7000000 for 7.0).
 * <p>
 * A Zabbix DB whose version is not listed here is treated as read-only and unsupported
 * (DESIGN.md 4.2).
 */
public enum ZabbixVersion {

	V6_0(6, 0),
	V7_0(7, 0),
	V8_0(8, 0);

	private final int major;
	private final int minor;

	ZabbixVersion(int major, int minor) {
		this.major = major;
		this.minor = minor;
	}

	public int major() {
		return major;
	}

	public int minor() {
		return minor;
	}

	public String label() {
		return major + "." + minor;
	}

	/** @param mandatory value of {@code dbversion.mandatory} */
	public static Optional<ZabbixVersion> fromDbVersion(int mandatory) {
		int major = mandatory / 1_000_000;
		int minor = (mandatory / 10_000) % 100;
		for (ZabbixVersion v : values()) {
			if (v.major == major && v.minor == minor) {
				return Optional.of(v);
			}
		}
		return Optional.empty();
	}

	/** @param label "6.0", "7.0", ... */
	public static Optional<ZabbixVersion> fromLabel(String label) {
		for (ZabbixVersion v : values()) {
			if (v.label().equals(label)) {
				return Optional.of(v);
			}
		}
		return Optional.empty();
	}

	/** Major.minor read from {@code dbversion.mandatory}, also for unsupported versions. */
	public static String labelOf(int mandatory) {
		return (mandatory / 1_000_000) + "." + ((mandatory / 10_000) % 100);
	}
}
