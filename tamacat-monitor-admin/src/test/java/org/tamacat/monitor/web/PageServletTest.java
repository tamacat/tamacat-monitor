package org.tamacat.monitor.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.tamacat.monitor.web.page.PageServletAccess;

class PageServletTest {

	private static final String BS = "\\";
	private static final char LINE_SEPARATOR = (char) 0x2028;

	@Test
	void bootstrapJsonCannotCloseTheScriptElement() {
		String safe = PageServletAccess.scriptSafe("{\"name\":\"</script><script>alert(1)</script>&" + LINE_SEPARATOR + "\"}");
		assertFalse(safe.contains("<"));
		assertFalse(safe.contains(">"));
		assertFalse(safe.contains("&"));
		assertFalse(safe.indexOf(LINE_SEPARATOR) >= 0);
		assertEquals("{\"name\":\"" + BS + "u003c/script" + BS + "u003e" + BS + "u003cscript" + BS + "u003ealert(1)"
				+ BS + "u003c/script" + BS + "u003e" + BS + "u0026" + BS + "u2028\"}", safe);
	}
}
