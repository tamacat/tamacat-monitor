package org.tamacat.monitor.web.api;

import java.io.IOException;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.tamacat.monitor.user.User;
import org.tamacat.monitor.user.UserService.AuthResult;
import org.tamacat.monitor.web.WebSession;
import org.tamacat.monitor.web.WebSession.Ceremony;

/**
 * Sign-in and sign-out.
 * <pre>
 * POST /api/login                      {"loginId","password"}  -> {"user","csrfToken"} or {"mfaRequired", "methods":["totp","passkey"]}
 * POST /api/login/mfa                  {"code"}                second step: TOTP or recovery code
 * POST /api/login/mfa/passkey/options                          second step: options for the user's passkeys
 * POST /api/login/mfa/passkey          {"credential"}          second step: a passkey
 * POST /api/login/passkey/options                              sign-in with a passkey alone: options
 * POST /api/login/passkey              {"credential"}          sign-in with a passkey alone
 * POST /api/logout
 * </pre>
 * A failed login always answers the same generic error (no hint whether the user exists or is locked).
 */
@WebServlet(urlPatterns = { "/api/login", "/api/login/*", "/api/logout" })
public class SessionApi extends ApiServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
		String path = req.getServletPath() + (req.getPathInfo() == null ? "" : req.getPathInfo());
		switch (path) {
			case "/api/logout" -> {
				WebSession.logout(req);
				ApiSupport.write(res, 200, Json.createObjectBuilder().add("ok", true).build());
			}
			case "/api/login" -> login(req, res);
			case "/api/login/mfa" -> loginMfa(req, res);
			case "/api/login/mfa/passkey/options" -> secondFactorOptions(req, res);
			case "/api/login/mfa/passkey" -> secondFactor(req, res);
			case "/api/login/passkey/options" -> passkeyOptions(req, res);
			case "/api/login/passkey" -> passkeyLogin(req, res);
			default -> throw new org.tamacat.monitor.common.NotFoundException(req.getRequestURI());
		}
	}

	private void login(HttpServletRequest req, HttpServletResponse res) throws IOException {
		JsonObject body = ApiSupport.readObject(req);
		AuthResult r = app().users().authenticate(ApiSupport.str(body, "loginId"), orEmpty(ApiSupport.chars(body, "password")),
				WebSession.clientIp(req));
		if (r instanceof AuthResult.Success s && s.user().hasSecondFactor()) {
			// password right: the session is authenticated only after the second step
			WebSession.startMfa(req, s.user().userId(), now());
			JsonArrayBuilder methods = Json.createArrayBuilder();
			if (s.user().mfaEnabled()) {
				methods.add("totp");
			}
			if (s.user().passkeyCount() > 0 && app().passkeys().enabled()) {
				methods.add("passkey");
			}
			ApiSupport.write(res, 200, Json.createObjectBuilder().add("mfaRequired", true).add("methods", methods).build());
		} else if (r instanceof AuthResult.Success s) {
			signedIn(req, res, s.user());
		} else {
			ApiSupport.error(res, HttpServletResponse.SC_UNAUTHORIZED, "error.login");
		}
	}

	/** Second step: {"code"} = TOTP code or recovery code. */
	private void loginMfa(HttpServletRequest req, HttpServletResponse res) throws IOException {
		JsonObject body = ApiSupport.readObject(req);
		Long userId = WebSession.pendingMfa(req, now());
		if (userId == null) {
			ApiSupport.error(res, HttpServletResponse.SC_UNAUTHORIZED, "error.mfa.expired");
			return;
		}
		var user = app().users().get(userId);
		if (user.enabled() && app().mfa().verifyLogin(user, ApiSupport.str(body, "code"), WebSession.actor(req))) {
			signedIn(req, res, app().users().get(userId));
		} else {
			WebSession.mfaFailed(req);
			ApiSupport.error(res, HttpServletResponse.SC_UNAUTHORIZED, "error.mfa.code");
		}
	}

	private void secondFactorOptions(HttpServletRequest req, HttpServletResponse res) throws IOException {
		Long userId = WebSession.pendingMfa(req, now());
		if (userId == null) {
			ApiSupport.error(res, HttpServletResponse.SC_UNAUTHORIZED, "error.mfa.expired");
			return;
		}
		var options = app().passkeys().startSecondFactor(userId);
		WebSession.setChallenge(req, Ceremony.SECOND_FACTOR, options.challenge(), now());
		ApiSupport.write(res, 200, PasskeyJson.requestOptions(app().passkeys().relyingParty(), options, false));
	}

	private void secondFactor(HttpServletRequest req, HttpServletResponse res) throws IOException {
		JsonObject body = ApiSupport.readObject(req);
		Long userId = WebSession.pendingMfa(req, now());
		byte[] challenge = WebSession.takeChallenge(req, Ceremony.SECOND_FACTOR, now());
		if (userId == null || challenge == null) {
			ApiSupport.error(res, HttpServletResponse.SC_UNAUTHORIZED, "error.mfa.expired");
			return;
		}
		var user = app().users().get(userId);
		if (user.enabled() && app().passkeys().verifySecondFactor(user, challenge, PasskeyJson.assertion(credential(body)),
				WebSession.actor(req))) {
			signedIn(req, res, app().users().get(userId));
		} else {
			WebSession.mfaFailed(req);
			ApiSupport.error(res, HttpServletResponse.SC_UNAUTHORIZED, "error.passkey.failed");
		}
	}

	private void passkeyOptions(HttpServletRequest req, HttpServletResponse res) throws IOException {
		var options = app().passkeys().startLogin();
		WebSession.setChallenge(req, Ceremony.LOGIN, options.challenge(), now());
		ApiSupport.write(res, 200, PasskeyJson.requestOptions(app().passkeys().relyingParty(), options, true));
	}

	private void passkeyLogin(HttpServletRequest req, HttpServletResponse res) throws IOException {
		JsonObject body = ApiSupport.readObject(req);
		byte[] challenge = WebSession.takeChallenge(req, Ceremony.LOGIN, now());
		if (challenge == null) {
			ApiSupport.error(res, HttpServletResponse.SC_UNAUTHORIZED, "error.passkey.failed");
			return;
		}
		var user = app().passkeys().login(challenge, PasskeyJson.assertion(credential(body)), WebSession.clientIp(req));
		if (user.isPresent()) {
			signedIn(req, res, user.get());
		} else {
			ApiSupport.error(res, HttpServletResponse.SC_UNAUTHORIZED, "error.passkey.failed");
		}
	}

	private static void signedIn(HttpServletRequest req, HttpServletResponse res, User user) throws IOException {
		WebSession.login(req, user.userId());
		ApiSupport.write(res, 200, Json.createObjectBuilder()
				.add("user", ApiSupport.user(user))
				.add("csrfToken", WebSession.csrfToken(req))
				.build());
	}

	private static JsonObject credential(JsonObject body) {
		return body.get("credential") instanceof JsonObject c ? c : null;
	}

	private long now() {
		return app().clock().instant().getEpochSecond();
	}

	private static char[] orEmpty(char[] c) {
		return c == null ? new char[0] : c;
	}
}
