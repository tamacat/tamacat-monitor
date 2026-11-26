package org.tamacat.monitor.web.api;

import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.tamacat.monitor.security.webauthn.CoseKey;
import org.tamacat.monitor.security.webauthn.WebAuthn;
import org.tamacat.monitor.security.webauthn.WebAuthnException;
import org.tamacat.monitor.user.PasskeyService;
import org.tamacat.monitor.user.User;

/**
 * WebAuthn options and credentials as JSON, in the shape of the WebAuthn Level 3 JSON types
 * (PublicKeyCredentialCreationOptionsJSON, ...RequestOptionsJSON, RegistrationResponseJSON,
 * AuthenticationResponseJSON): binary values are base64url.
 */
final class PasskeyJson {

	/** Time the browser waits for the user (ms). */
	static final int TIMEOUT_MS = 300_000;

	private PasskeyJson() {
	}

	/** Discoverable credential with user verification: usable for sign-in without a password. */
	static JsonObject creationOptions(WebAuthn.RelyingParty rp, User user, PasskeyService.CreationOptions o) {
		JsonArrayBuilder params = Json.createArrayBuilder();
		for (int alg : new int[] { CoseKey.ES256, CoseKey.EDDSA, CoseKey.RS256 }) {
			params.add(Json.createObjectBuilder().add("type", "public-key").add("alg", alg));
		}
		return Json.createObjectBuilder()
				.add("rp", Json.createObjectBuilder().add("id", rp.id()).add("name", rp.name()))
				.add("user", Json.createObjectBuilder().add("id", WebAuthn.base64Url(o.userHandle())).add("name", user.loginId())
						.add("displayName", user.displayName()))
				.add("challenge", WebAuthn.base64Url(o.challenge()))
				.add("pubKeyCredParams", params)
				.add("timeout", TIMEOUT_MS)
				.add("excludeCredentials", descriptors(o.excludeCredentials()))
				.add("authenticatorSelection", Json.createObjectBuilder().add("residentKey", "required")
						.add("requireResidentKey", true).add("userVerification", "required"))
				.add("attestation", "none")
				.build();
	}

	static JsonObject requestOptions(WebAuthn.RelyingParty rp, PasskeyService.RequestOptions o, boolean requireUserVerification) {
		return Json.createObjectBuilder()
				.add("rpId", rp.id())
				.add("challenge", WebAuthn.base64Url(o.challenge()))
				.add("timeout", TIMEOUT_MS)
				.add("allowCredentials", descriptors(o.allowCredentials()))
				.add("userVerification", requireUserVerification ? "required" : "preferred")
				.build();
	}

	private static JsonArrayBuilder descriptors(List<byte[]> ids) {
		JsonArrayBuilder a = Json.createArrayBuilder();
		ids.forEach(id -> a.add(Json.createObjectBuilder().add("type", "public-key").add("id", WebAuthn.base64Url(id))));
		return a;
	}

	/** A registration response: {"response": {"clientDataJSON", "attestationObject", "transports"}}. */
	record Registration(byte[] clientDataJson, byte[] attestationObject, String transports) {
	}

	static Registration registration(JsonObject credential) {
		JsonObject r = response(credential);
		StringBuilder transports = new StringBuilder();
		if (r.get("transports") instanceof JsonArray arr) {
			for (JsonValue v : arr) {
				if (v instanceof JsonString s && transports.length() < 150) {
					transports.append(transports.isEmpty() ? "" : ",").append(s.getString());
				}
			}
		}
		return new Registration(bin(r, "clientDataJSON"), bin(r, "attestationObject"), transports.toString());
	}

	/** An authentication response: {"rawId", "response": {"clientDataJSON", "authenticatorData", "signature", "userHandle"}}. */
	static PasskeyService.AssertionResponse assertion(JsonObject credential) {
		JsonObject r = response(credential);
		byte[] userHandle = r.get("userHandle") instanceof JsonString s && !s.getString().isEmpty() ? decode(s.getString()) : null;
		return new PasskeyService.AssertionResponse(bin(credential, "rawId"), bin(r, "clientDataJSON"), bin(r, "authenticatorData"),
				bin(r, "signature"), userHandle);
	}

	static JsonObjectBuilder passkey(PasskeyService.Passkey p) {
		JsonObjectBuilder b = Json.createObjectBuilder().add("passkeyId", p.passkeyId()).add("name", p.name())
				.add("synced", p.backedUp()).add("createdAt", p.createdAt().toString());
		if (p.lastUsedAt() != null) {
			b.add("lastUsedAt", p.lastUsedAt().toString());
		}
		return b;
	}

	private static JsonObject response(JsonObject credential) {
		if (credential == null || !(credential.get("response") instanceof JsonObject r) || !"public-key".equals(credential.getString("type", ""))) {
			throw new BadRequestException("credential");
		}
		return r;
	}

	private static byte[] bin(JsonObject o, String key) {
		if (o.get(key) instanceof JsonString s) {
			return decode(s.getString());
		}
		throw new BadRequestException(key);
	}

	private static byte[] decode(String s) {
		if (s.length() > 64 * 1024) {
			throw new BadRequestException("too long");
		}
		try {
			return WebAuthn.fromBase64Url(s);
		} catch (WebAuthnException e) {
			throw new BadRequestException("not base64url");
		}
	}
}
