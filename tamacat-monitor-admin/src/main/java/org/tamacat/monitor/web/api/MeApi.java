package org.tamacat.monitor.web.api;

import java.io.IOException;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.tamacat.monitor.user.User;
import org.tamacat.monitor.web.WebSession;

/**
 * The logged-in user.
 * <pre>
 * GET  /api/me
 * POST /api/me/password {"currentPassword","newPassword"}
 * PUT  /api/me/locale   {"locale":"ja"}
 * GET  /api/me/mfa                       {"enabled", "recoveryCodesLeft"}
 * POST /api/me/mfa/setup                 a new secret: {"secret", "uri"} (kept in the session)
 * POST /api/me/mfa/enable  {"password","code"}   enables it: {"recoveryCodes":[...]} (shown once)
 * POST /api/me/mfa/disable {"password","code"}
 * GET  /api/me/passkeys                  {"enabled", "passkeys":[...]}
 * POST /api/me/passkeys/options {"password"}       creation options (the challenge is kept in the session)
 * POST /api/me/passkeys         {"name","credential"}  registers the passkey
 * PUT  /api/me/passkeys/{id}    {"name"}
 * POST /api/me/passkeys/{id}/delete {"password"}
 * </pre>
 */
@WebServlet(urlPatterns = { "/api/me", "/api/me/*" })
public class MeApi extends ApiServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
		String[] seg = segments(req);
		if (seg.length == 1 && seg[0].equals("passkeys")) {
			var list = Json.createArrayBuilder();
			app().passkeys().list(user(req).userId()).forEach(p -> list.add(PasskeyJson.passkey(p)));
			ApiSupport.write(res, 200, Json.createObjectBuilder().add("enabled", app().passkeys().enabled()).add("passkeys", list).build());
			return;
		}
		if (seg.length == 1 && seg[0].equals("mfa")) {
			User u = user(req);
			ApiSupport.write(res, 200, Json.createObjectBuilder().add("enabled", u.mfaEnabled())
					.add("recoveryCodesLeft", u.mfaEnabled() ? app().mfa().remainingRecoveryCodes(u.userId()) : 0).build());
			return;
		}
		ApiSupport.write(res, 200, ApiSupport.user(user(req)).build());
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
		String[] seg = segments(req);
		if (seg.length == 2 && seg[0].equals("mfa")) {
			mfa(req, res, seg[1]);
			return;
		}
		if (seg.length >= 1 && seg[0].equals("passkeys")) {
			passkeys(req, res, seg);
			return;
		}
		if (seg.length != 1 || !seg[0].equals("password")) {
			throw new org.tamacat.monitor.common.NotFoundException(req.getRequestURI());
		}
		User u = user(req);
		JsonObject body = ApiSupport.readObject(req);
		app().users().changeOwnPassword(u.userId(), ApiSupport.chars(body, "currentPassword"),
				ApiSupport.chars(body, "newPassword"), WebSession.actor(req));
		ApiSupport.write(res, 200, Json.createObjectBuilder().add("ok", true).build());
	}

	private void mfa(HttpServletRequest req, HttpServletResponse res, String op) throws IOException {
		User u = user(req);
		switch (op) {
			case "setup" -> {
				var setup = app().mfa().newSetup(u);
				WebSession.setMfaSetup(req, setup.secret());
				ApiSupport.write(res, 200, Json.createObjectBuilder().add("secret", setup.secret()).add("uri", setup.uri()).build());
			}
			case "enable" -> {
				String secret = WebSession.mfaSetup(req);
				if (secret == null) {
					throw new org.tamacat.monitor.common.ConflictException("error.mfa.noSetup");
				}
				JsonObject b = ApiSupport.readObject(req);
				var codes = app().mfa().enable(u, secret, ApiSupport.chars(b, "password"), ApiSupport.str(b, "code"), WebSession.actor(req));
				WebSession.clearMfaSetup(req);
				var arr = Json.createArrayBuilder();
				codes.forEach(arr::add);
				ApiSupport.write(res, 200, Json.createObjectBuilder().add("recoveryCodes", arr).build());
			}
			case "disable" -> {
				JsonObject b = ApiSupport.readObject(req);
				app().mfa().disable(u, ApiSupport.chars(b, "password"), ApiSupport.str(b, "code"), WebSession.actor(req));
				ApiSupport.write(res, 200, Json.createObjectBuilder().add("ok", true).build());
			}
			default -> throw new org.tamacat.monitor.common.NotFoundException(req.getRequestURI());
		}
	}

	private void passkeys(HttpServletRequest req, HttpServletResponse res, String[] seg) throws IOException {
		User u = user(req);
		JsonObject b = ApiSupport.readObject(req);
		long now = app().clock().instant().getEpochSecond();
		if (seg.length == 2 && seg[1].equals("options")) {
			var options = app().passkeys().startRegistration(u, ApiSupport.chars(b, "password"), WebSession.actor(req));
			WebSession.setChallenge(req, WebSession.Ceremony.REGISTER, options.challenge(), now);
			ApiSupport.write(res, 200, PasskeyJson.creationOptions(app().passkeys().relyingParty(), u, options));
		} else if (seg.length == 1) {
			byte[] challenge = WebSession.takeChallenge(req, WebSession.Ceremony.REGISTER, now);
			if (challenge == null) {
				throw new org.tamacat.monitor.common.ConflictException("error.passkey.expired");
			}
			var r = PasskeyJson.registration(b.get("credential") instanceof JsonObject c ? c : null);
			var p = app().passkeys().finishRegistration(u, challenge, ApiSupport.str(b, "name"), r.clientDataJson(),
					r.attestationObject(), r.transports(), WebSession.actor(req));
			ApiSupport.write(res, 201, PasskeyJson.passkey(p).build());
		} else if (seg.length == 3 && seg[2].equals("delete")) {
			app().passkeys().delete(u, id(seg[1]), ApiSupport.chars(b, "password"), WebSession.actor(req));
			ApiSupport.write(res, 200, Json.createObjectBuilder().add("ok", true).build());
		} else {
			throw new org.tamacat.monitor.common.NotFoundException(req.getRequestURI());
		}
	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse res) throws IOException {
		String[] seg = segments(req);
		if (seg.length == 2 && seg[0].equals("passkeys")) {
			app().passkeys().rename(user(req), id(seg[1]), ApiSupport.str(ApiSupport.readObject(req), "name"), WebSession.actor(req));
			ApiSupport.write(res, 200, Json.createObjectBuilder().add("ok", true).build());
			return;
		}
		if (seg.length != 1 || !seg[0].equals("locale")) {
			throw new org.tamacat.monitor.common.NotFoundException(req.getRequestURI());
		}
		User u = user(req);
		app().users().setLocale(u.userId(), ApiSupport.str(ApiSupport.readObject(req), "locale"));
		ApiSupport.write(res, 200, Json.createObjectBuilder().add("ok", true).build());
	}
}
