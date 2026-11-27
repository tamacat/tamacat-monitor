package org.tamacat.monitor.zabbix.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.zabbix.reader.HistoryReader.GraphQuery;
import org.tamacat.monitor.zabbix.reader.HistoryReader.ItemRef;
import org.tamacat.monitor.zabbix.reader.HistoryReader.Source;

class HistoryReaderTest {

	private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");

	private static ItemRef item(String history, String trends) {
		return new ItemRef(1, 1, "h", "h", "n", "k", 0, "", history, trends);
	}

	private static GraphQuery last(Duration d, Source s) {
		return new GraphQuery(List.of(1L), NOW.minus(d), NOW, s, 500);
	}

	@Test
	void retention() {
		assertEquals(Duration.ofDays(90), HistoryReader.retention("90d"));
		assertEquals(Duration.ofDays(14), HistoryReader.retention("2w"));
		assertEquals(Duration.ofHours(1), HistoryReader.retention("3600"));
		assertEquals(Duration.ZERO, HistoryReader.retention("0"));
		assertNull(HistoryReader.retention("{$HISTORY}"));
		assertNull(HistoryReader.retention(null));
	}

	@Test
	void autoUsesHistoryWithinRetention() {
		assertFalse(HistoryReader.useTrends(item("7d", "365d"), last(Duration.ofDays(1), Source.AUTO), NOW));
		assertTrue(HistoryReader.useTrends(item("7d", "365d"), last(Duration.ofDays(8), Source.AUTO), NOW));
		assertFalse(HistoryReader.useTrends(item("90d", "365d"), last(Duration.ofDays(30), Source.AUTO), NOW));
		assertTrue(HistoryReader.useTrends(item("90d", "365d"), last(Duration.ofDays(60), Source.AUTO), NOW), "longer than 31 days");
		// macro: 7 days assumed
		assertTrue(HistoryReader.useTrends(item("{$H}", "365d"), last(Duration.ofDays(10), Source.AUTO), NOW));
	}

	@Test
	void itemsWithoutTrends() {
		assertFalse(HistoryReader.useTrends(item("7d", "0"), last(Duration.ofDays(10), Source.AUTO), NOW));
		assertThrows(ValidationException.class, () -> HistoryReader.useTrends(item("7d", "0"), last(Duration.ofDays(40), Source.AUTO), NOW));
		assertThrows(ValidationException.class, () -> HistoryReader.useTrends(item("7d", "0"), last(Duration.ofDays(1), Source.TRENDS), NOW));
	}

	@Test
	void explicitSource() {
		assertFalse(HistoryReader.useTrends(item("1d", "365d"), last(Duration.ofDays(5), Source.HISTORY), NOW));
		assertTrue(HistoryReader.useTrends(item("90d", "365d"), last(Duration.ofHours(1), Source.TRENDS), NOW));
		assertEquals(Source.AUTO, Source.of(null));
		assertEquals(Source.TRENDS, Source.of("trends"));
		assertThrows(ValidationException.class, () -> Source.of("x"));
	}

	@Test
	void period() {
		HistoryReader.validate(NOW.minus(Duration.ofDays(31)), NOW, HistoryReader.MAX_HISTORY_PERIOD);
		assertThrows(ValidationException.class,
				() -> HistoryReader.validate(NOW.minus(Duration.ofDays(32)), NOW, HistoryReader.MAX_HISTORY_PERIOD));
		assertThrows(ValidationException.class, () -> HistoryReader.validate(NOW, NOW, HistoryReader.MAX_HISTORY_PERIOD));
	}
}
