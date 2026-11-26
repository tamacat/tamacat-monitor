package org.tamacat.monitor.web.page;

import java.io.IOException;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.tamacat.monitor.user.Role;
import org.tamacat.monitor.user.User;
import org.tamacat.monitor.web.AppContext;
import org.tamacat.monitor.web.Messages;
import org.tamacat.monitor.web.WebSession;

/**
 * Renders a page shell ({@code /WEB-INF/jsp/page.jsp}) that loads the Cloudscape bundle of the page.
 * Page data comes from the JSON APIs; the shell only passes the bootstrap object
 * (user, CSRF token, locale, messages).
 */
@WebServlet(urlPatterns = { "", "/login", "/users", "/servers", "/account", "/problems", "/hosts", "/hostgroups", "/items",
		"/alerts", "/actions", "/server" })
public class PageServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	/** path, bundle name, title key, required role (null: any logged-in user) */
	private record PageDef(String bundle, String titleKey, Role role) {
	}

	private static final Map<String, PageDef> PAGES = Map.ofEntries(
			Map.entry("", new PageDef("home", "nav.home", null)),
			Map.entry("/login", new PageDef("login", "login.title", null)),
			Map.entry("/users", new PageDef("users", "nav.users", Role.SYSTEM_ADMIN)),
			Map.entry("/servers", new PageDef("servers", "nav.servers", Role.SYSTEM_ADMIN)),
			Map.entry("/account", new PageDef("account", "nav.account", null)),
			// P2: data is limited by the API (accessible servers and host groups)
			Map.entry("/problems", new PageDef("problems", "nav.problems", null)),
			Map.entry("/hosts", new PageDef("hosts", "nav.hosts", null)),
			Map.entry("/hostgroups", new PageDef("hostgroups", "nav.hostgroups", null)),
			Map.entry("/items", new PageDef("items", "nav.items", null)),
			Map.entry("/alerts", new PageDef("alerts", "nav.alerts", null)),
			Map.entry("/actions", new PageDef("actions", "nav.actions", null)),
			// P3: per-server dashboard (?id=); data limited by the API
			Map.entry("/server", new PageDef("server", "dashboard.serverTitle", null)));

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		PageDef page = PAGES.get(req.getServletPath());
		if (page == null) {
			res.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		User user = WebSession.currentUser(req);
		if (page.bundle().equals("login") && WebSession.userId(req.getSession(false)) != null) {
			res.sendRedirect(req.getContextPath() + "/");
			return;
		}
		if (page.role() != null && (user == null || !user.has(page.role()))) {
			res.sendRedirect(req.getContextPath() + "/");
			return;
		}
		AppContext app = AppContext.of(getServletContext());
		String locale = WebSession.locale(req);
		Map<String, String> messages = Messages.all(locale);

		JsonObjectBuilder msg = Json.createObjectBuilder();
		messages.forEach(msg::add);
		JsonObjectBuilder boot = Json.createObjectBuilder()
				.add("page", page.bundle())
				.add("contextPath", req.getContextPath())
				.add("csrfToken", WebSession.csrfToken(req))
				.add("locale", locale)
				.add("version", app.version())
				.add("passkeys", app.passkeys().enabled())
				.add("messages", msg);
		if (user != null) {
			JsonObjectBuilder u = Json.createObjectBuilder()
					.add("userId", user.userId())
					.add("loginId", user.loginId())
					.add("displayName", user.displayName())
					.add("mustChangePassword", user.mustChangePassword());
			var roles = Json.createArrayBuilder();
			user.roles().stream().sorted().forEach(r -> roles.add(r.name()));
			boot.add("user", u.add("roles", roles));
		}

		req.setAttribute("page", page.bundle());
		req.setAttribute("locale", locale);
		req.setAttribute("version", app.version());
		req.setAttribute("pageTitle", messages.getOrDefault(page.titleKey(), page.titleKey()));
		req.setAttribute("appTitle", messages.getOrDefault("app.title", "tamacat-monitor"));
		req.setAttribute("noscript", messages.getOrDefault("app.noscript", "JavaScript is required."));
		req.setAttribute("bootstrapJson", scriptSafe(boot.build().toString()));
		req.getRequestDispatcher("/WEB-INF/jsp/page.jsp").forward(req, res);
	}

	/**
	 * Makes JSON safe inside a {@code <script type="application/json">} element: "<", ">", "&"
	 * and line separators only occur inside JSON strings, where their \\u escapes are equivalent.
	 */
	static String scriptSafe(String json) {
		return json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
				.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
	}
}
