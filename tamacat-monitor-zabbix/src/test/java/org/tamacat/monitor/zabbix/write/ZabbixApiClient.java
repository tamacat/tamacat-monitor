package org.tamacat.monitor.zabbix.write;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * Minimal Zabbix JSON-RPC client for golden-master tests only (tamacat-monitor itself never calls the API).
 * 6.0 sends the session in "auth"; 7.0 in the Authorization header.
 */
final class ZabbixApiClient {

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final URI uri;
	private final boolean bearer;
	private String token;

	ZabbixApiClient(String apiUrl, boolean bearer) {
		this.uri = URI.create(apiUrl);
		this.bearer = bearer;
	}

	ZabbixApiClient login(String user, String password) {
		token = call("user.login", Json.createObjectBuilder().add("username", user).add("password", password).build()).toString()
				.replace("\"", "");
		return this;
	}

	JsonValue call(String method, JsonValue params) {
		var body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("method", method).add("params", params).add("id", 1);
		var req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json-rpc");
		if (token != null) {
			if (bearer) {
				req.header("Authorization", "Bearer " + token);
			} else {
				body.add("auth", token);
			}
		}
		try {
			HttpResponse<String> res = http.send(req.POST(HttpRequest.BodyPublishers.ofString(body.build().toString())).build(),
					HttpResponse.BodyHandlers.ofString());
			JsonObject o = Json.createReader(new StringReader(res.body())).readObject();
			if (o.containsKey("error")) {
				throw new IllegalStateException(method + ": " + o.get("error"));
			}
			return o.get("result");
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/** First id of a create / update / delete result such as {"groupids":["20"]}. */
	static long firstId(JsonValue result, String key) {
		return Long.parseLong(result.asJsonObject().getJsonArray(key).getString(0));
	}
}
