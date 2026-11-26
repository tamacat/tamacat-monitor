package org.tamacat.monitor.web.page;

/** Test access to package-private helpers. */
public final class PageServletAccess {
	private PageServletAccess() {
	}

	public static String scriptSafe(String json) {
		return PageServlet.scriptSafe(json);
	}
}
