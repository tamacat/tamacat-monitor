/*
 * Copyright (c) 2026 tamacat.org
 * All rights reserved.
 */
package org.tamacat.sql.pool;

import java.time.Duration;
import java.util.Objects;

/**
 * Connection pool settings.
 *
 * @param name                   pool name (used in logs and statistics)
 * @param minIdle                connections kept open while idle
 * @param maxSize                upper bound of open connections
 * @param connectionTimeout      how long {@code getConnection()} waits
 * @param idleTimeout            idle connections beyond minIdle are closed after this
 * @param maxLifetime            connections are replaced after this age
 * @param validationInterval     a connection idle for longer than this is checked with isValid before use
 * @param validationTimeout      timeout of the isValid check (at least 1 second)
 * @param leakDetectionThreshold warn when a connection is held longer than this; zero disables
 * @param housekeepingInterval   period of eviction, leak detection and refill
 */
public record PoolConfig(
		String name,
		int minIdle,
		int maxSize,
		Duration connectionTimeout,
		Duration idleTimeout,
		Duration maxLifetime,
		Duration validationInterval,
		Duration validationTimeout,
		Duration leakDetectionThreshold,
		Duration housekeepingInterval) {

	public PoolConfig {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name is required");
		}
		if (maxSize < 1) {
			throw new IllegalArgumentException("maxSize must be >= 1");
		}
		if (minIdle < 0 || minIdle > maxSize) {
			throw new IllegalArgumentException("minIdle must be 0..maxSize");
		}
		requirePositive(connectionTimeout, "connectionTimeout");
		requirePositive(idleTimeout, "idleTimeout");
		requirePositive(maxLifetime, "maxLifetime");
		requirePositive(validationInterval, "validationInterval");
		requirePositive(housekeepingInterval, "housekeepingInterval");
		Objects.requireNonNull(validationTimeout, "validationTimeout");
		if (validationTimeout.toSeconds() < 1) {
			throw new IllegalArgumentException("validationTimeout must be >= 1s");
		}
		Objects.requireNonNull(leakDetectionThreshold, "leakDetectionThreshold");
		if (leakDetectionThreshold.isNegative()) {
			throw new IllegalArgumentException("leakDetectionThreshold must be >= 0");
		}
	}

	private static void requirePositive(Duration d, String field) {
		Objects.requireNonNull(d, field);
		if (d.isNegative() || d.isZero()) {
			throw new IllegalArgumentException(field + " must be > 0");
		}
	}

	public static Builder builder(String name) {
		return new Builder(name);
	}

	public static final class Builder {
		private final String name;
		private int minIdle = 1;
		private int maxSize = 10;
		private Duration connectionTimeout = Duration.ofSeconds(30);
		private Duration idleTimeout = Duration.ofMinutes(10);
		private Duration maxLifetime = Duration.ofMinutes(30);
		private Duration validationInterval = Duration.ofSeconds(30);
		private Duration validationTimeout = Duration.ofSeconds(5);
		private Duration leakDetectionThreshold = Duration.ZERO;
		private Duration housekeepingInterval = Duration.ofSeconds(30);

		private Builder(String name) {
			this.name = name;
		}

		public Builder minIdle(int v) {
			minIdle = v;
			return this;
		}

		public Builder maxSize(int v) {
			maxSize = v;
			return this;
		}

		public Builder connectionTimeout(Duration v) {
			connectionTimeout = v;
			return this;
		}

		public Builder idleTimeout(Duration v) {
			idleTimeout = v;
			return this;
		}

		public Builder maxLifetime(Duration v) {
			maxLifetime = v;
			return this;
		}

		public Builder validationInterval(Duration v) {
			validationInterval = v;
			return this;
		}

		public Builder validationTimeout(Duration v) {
			validationTimeout = v;
			return this;
		}

		public Builder leakDetectionThreshold(Duration v) {
			leakDetectionThreshold = v;
			return this;
		}

		public Builder housekeepingInterval(Duration v) {
			housekeepingInterval = v;
			return this;
		}

		public PoolConfig build() {
			return new PoolConfig(name, minIdle, maxSize, connectionTimeout, idleTimeout, maxLifetime,
					validationInterval, validationTimeout, leakDetectionThreshold, housekeepingInterval);
		}
	}
}
