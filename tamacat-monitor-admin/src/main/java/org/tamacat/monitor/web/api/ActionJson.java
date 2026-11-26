package org.tamacat.monitor.web.api;

import java.util.ArrayList;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.tamacat.monitor.zabbix.reader.ActionReader;
import org.tamacat.monitor.zabbix.write.ActionWriter;
import org.tamacat.monitor.zabbix.write.ActionWriter.ActionInput;
import org.tamacat.monitor.zabbix.write.ActionWriter.Condition;
import org.tamacat.monitor.zabbix.write.ActionWriter.Message;
import org.tamacat.monitor.zabbix.write.ActionWriter.Operation;

/**
 * JSON form of a trigger action definition (T-407):
 * <pre>
 * {name, status, escPeriod, pauseSuppressed, notifyIfCanceled, pauseSymptoms, evalType,
 *  conditions: [{type, operator, value, value2}],
 *  operations / recoveryOperations / updateOperations: [{type, stepFrom, stepTo, escPeriod,
 *    message: {defaultMessage, subject, message, mediaTypeId (null = all), userGroupIds, userIds}}]}
 * </pre>
 */
final class ActionJson {

	private ActionJson() {
	}

	static ActionInput read(JsonObject b) {
		return new ActionInput(ApiSupport.str(b, "name"), intOr(b, "status", 0), ApiSupport.str(b, "escPeriod"),
				ApiSupport.bool(b, "pauseSuppressed", true), ApiSupport.bool(b, "notifyIfCanceled", true),
				ApiSupport.bool(b, "pauseSymptoms", true), intOr(b, "evalType", 0), conditions(b), operations(b, "operations"),
				operations(b, "recoveryOperations"), operations(b, "updateOperations"));
	}

	private static List<Condition> conditions(JsonObject b) {
		List<Condition> out = new ArrayList<>();
		for (JsonObject c : objects(b, "conditions")) {
			out.add(new Condition((int) ApiSupport.longValue(c, "type"), (int) ApiSupport.longValue(c, "operator"),
					ApiSupport.str(c, "value"), ApiSupport.str(c, "value2")));
		}
		return out;
	}

	private static List<Operation> operations(JsonObject b, String key) {
		List<Operation> out = new ArrayList<>();
		for (JsonObject o : objects(b, key)) {
			if (!(o.get("message") instanceof JsonObject m)) {
				throw new BadRequestException(key + ".message");
			}
			Long media = m.get("mediaTypeId") instanceof JsonNumber n ? n.longValueExact() : null;
			out.add(new Operation((int) ApiSupport.longValue(o, "type"), intOr(o, "stepFrom", 1), intOr(o, "stepTo", 1),
					ApiSupport.str(o, "escPeriod"), new Message(ApiSupport.bool(m, "defaultMessage", true), ApiSupport.str(m, "subject"),
							ApiSupport.str(m, "message"), media, List.copyOf(ApiSupport.longSet(m, "userGroupIds")),
							List.copyOf(ApiSupport.longSet(m, "userIds")))));
		}
		return out;
	}

	private static List<JsonObject> objects(JsonObject b, String key) {
		List<JsonObject> out = new ArrayList<>();
		if (b.get(key) instanceof JsonArray a) {
			for (JsonValue v : a) {
				if (!(v instanceof JsonObject o)) {
					throw new BadRequestException(key);
				}
				out.add(o);
			}
		}
		return out;
	}

	private static int intOr(JsonObject o, String key, int def) {
		return o.containsKey(key) && !o.isNull(key) ? (int) ApiSupport.longValue(o, key) : def;
	}

	static JsonObjectBuilder write(ActionWriter.Editable e) {
		ActionInput a = e.input();
		JsonArrayBuilder conditions = Json.createArrayBuilder();
		a.conditions().forEach(c -> conditions.add(Json.createObjectBuilder().add("type", c.type()).add("operator", c.operator())
				.add("value", c.value()).add("value2", c.value2())));
		JsonObjectBuilder input = Json.createObjectBuilder().add("name", a.name()).add("status", a.status())
				.add("escPeriod", a.escPeriod()).add("pauseSuppressed", a.pauseSuppressed())
				.add("notifyIfCanceled", a.notifyIfCanceled()).add("pauseSymptoms", a.pauseSymptoms()).add("evalType", a.evalType())
				.add("conditions", conditions).add("operations", ops(a.operations()))
				.add("recoveryOperations", ops(a.recoveryOperations())).add("updateOperations", ops(a.updateOperations()));
		JsonObjectBuilder b = Json.createObjectBuilder().add("input", input);
		if (e.reason() == null) {
			b.addNull("reason");
		} else {
			b.add("reason", e.reason());
		}
		return b;
	}

	private static JsonArrayBuilder ops(List<Operation> ops) {
		JsonArrayBuilder arr = Json.createArrayBuilder();
		for (Operation o : ops) {
			JsonObjectBuilder ob = Json.createObjectBuilder().add("type", o.type()).add("stepFrom", o.stepFrom())
					.add("stepTo", o.stepTo()).add("escPeriod", o.escPeriod());
			Message m = o.message();
			if (m != null) {
				JsonArrayBuilder g = Json.createArrayBuilder();
				m.userGroupIds().forEach(g::add);
				JsonArrayBuilder u = Json.createArrayBuilder();
				m.userIds().forEach(u::add);
				JsonObjectBuilder mb = Json.createObjectBuilder().add("defaultMessage", m.defaultMessage())
						.add("subject", m.subject() == null ? "" : m.subject()).add("message", m.message() == null ? "" : m.message())
						.add("userGroupIds", g).add("userIds", u);
				if (m.mediaTypeId() == null) {
					mb.addNull("mediaTypeId");
				} else {
					mb.add("mediaTypeId", m.mediaTypeId());
				}
				ob.add("message", mb);
			}
			arr.add(ob);
		}
		return arr;
	}

	static JsonObjectBuilder choices(ActionReader.Choices c) {
		return Json.createObjectBuilder().add("userGroups", list(c.userGroups())).add("users", list(c.users()))
				.add("mediaTypes", list(c.mediaTypes()));
	}

	private static JsonArrayBuilder list(List<ActionReader.Choice> l) {
		JsonArrayBuilder a = Json.createArrayBuilder();
		l.forEach(c -> a.add(Json.createObjectBuilder().add("id", c.id()).add("name", c.name())));
		return a;
	}
}
