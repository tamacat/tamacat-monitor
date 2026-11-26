package org.tamacat.monitor.zabbix.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tamacat.dao.PreparedSql;
import org.tamacat.sql.Database;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.zabbix.ZabbixDatabases;
import org.tamacat.monitor.zabbix.ZabbixSchema;
import org.tamacat.monitor.zabbix.ZabbixVersion;
import org.tamacat.monitor.zabbix.write.ActionWriter.ActionInput;
import org.tamacat.monitor.zabbix.write.ActionWriter.Condition;
import org.tamacat.monitor.zabbix.write.ActionWriter.Message;
import org.tamacat.monitor.zabbix.write.ActionWriter.Operation;

/** Golden-master tests of trigger action writes against the Zabbix API (T-407). */
class ActionGoldenMasterTest {

	static Stream<Arguments> servers() {
		return Stream.of(Arguments.of("60", ZabbixVersion.V6_0), Arguments.of("70", ZabbixVersion.V7_0), Arguments.of("80", ZabbixVersion.V8_0));
	}

	private static <R> R write(Database db, ZabbixVersion v, Function<WriteContext, R> work) {
		return db.inTransaction(tx -> work.apply(new WriteContext(tx, null, ZabbixSchema.of(v), new IdAllocator())));
	}

	@ParameterizedTest
	@MethodSource("servers")
	void actionWritesEqualTheZabbixApi(String v, ZabbixVersion version) {
		String url = System.getenv("TMON_IT_ZABBIX" + v + "_URL");
		String apiUrl = System.getenv("TMON_IT_ZABBIX" + v + "_API");
		assumeTrue(url != null && apiUrl != null, "TMON_IT_ZABBIX" + v + "_URL / _API not set");
		ZabbixApiClient api = new ZabbixApiClient(apiUrl, version != ZabbixVersion.V6_0).login("Admin", "zabbix");
		String name = "gm-action-" + UUID.randomUUID().toString().substring(0, 6);

		ZabbixDatabases.withSingleConnection(url, "zabbix", "zabbix", db -> {
			long linux = id(db, "select groupid from hstgrp where name = 'Linux servers'");
			long zbxHost = id(db, "select hostid from hosts where host = 'Zabbix server'");
			long admins = id(db, "select usrgrpid from usrgrp where name = 'Zabbix administrators'");
			long guests = id(db, "select usrgrpid from usrgrp where name = 'Guests'");
			long admin = id(db, "select userid from users where username = 'Admin'");
			long email = id(db, "select min(mediatypeid) from media_type where type = 0");
			GoldenMaster gm = new GoldenMaster(db);
			ActionWriter writer = new ActionWriter();

			// --- create ---
			ActionInput created = new ActionInput(name, 0, "30m", true, true, true, 0,
					List.of(new Condition(0, 0, String.valueOf(linux), ""), new Condition(4, 5, "3", ""),
							new Condition(26, 2, "prod", "env")),
					List.of(new Operation(0, 1, 2, "0", new Message(true, null, null, null, List.of(admins), List.of(admin)))),
					List.of(new Operation(11, 1, 1, "0", new Message(true, null, null, null, List.of(), List.of()))),
					List.of(new Operation(0, 1, 1, "0", new Message(false, "S", "M", email, List.of(admins), List.of()))));
			ActionInput updated = new ActionInput(name + "-r", 1, "1h", false, true, false, 2,
					List.of(new Condition(0, 1, String.valueOf(linux), ""), new Condition(1, 0, String.valueOf(zbxHost), ""),
							new Condition(25, 0, "service", ""), new Condition(26, 3, "test", "env")),
					List.of(new Operation(0, 1, 1, "5m", new Message(false, "Problem", "Body", email, List.of(guests), List.of(admin))),
							new Operation(0, 2, 0, "0", new Message(true, null, null, null, List.of(admins), List.of()))),
					List.of(new Operation(0, 1, 1, "0", new Message(true, null, null, null, List.of(admins), List.of()))),
					List.of());

			long[] id = new long[1];
			var byApi = gm.capture(() -> id[0] = ZabbixApiClient.firstId(api.call("action.create", json(created, null, version)),
					"actionids"));
			var updApi = gm.capture(() -> api.call("action.update", json(updated, id[0], version)));
			api.call("action.delete", Json.createArrayBuilder().add(String.valueOf(id[0])).build());

			var byWriter = gm.capture(() -> id[0] = write(db, version, ctx -> writer.create(ctx, created)));
			try {
				assertEquals(byApi, byWriter, "action create");
				var updWriter = gm.capture(() -> write(db, version, ctx -> {
					writer.update(ctx, id[0], name, updated);
					return null;
				}));
				assertEquals(updApi, updWriter, "action update");

				// refusals
				assertThrows(ConflictException.class, () -> write(db, version, ctx -> writer.create(ctx, updated)), "duplicate name");
				assertThrows(ConflictException.class, () -> write(db, version, ctx -> {
					writer.update(ctx, id[0], name, updated); // stale name
					return null;
				}));
				long discovery = id(db, "select min(actionid) from actions where eventsource = 1");
				String dname = db.queryOne(PreparedSql.of("select name from actions where actionid = ?", discovery), r -> r.getString(1))
						.orElseThrow();
				ConflictException e = assertThrows(ConflictException.class, () -> write(db, version, ctx -> {
					writer.update(ctx, discovery, dname, updated);
					return null;
				}));
				assertEquals("error.action.eventSource", e.messageKey());
				ActionInput noRecipients = new ActionInput(name + "-x", 0, "1h", true, true, true, 0, List.of(),
						List.of(new Operation(0, 1, 1, "0", new Message(true, null, null, null, List.of(), List.of()))), List.of(), List.of());
				assertThrows(ValidationException.class, () -> write(db, version, ctx -> writer.create(ctx, noRecipients)));
				ActionInput badPeriod = new ActionInput(name + "-x", 0, "10s", true, true, true, 0, List.of(), List.of(), List.of(),
						List.of());
				assertThrows(ValidationException.class, () -> write(db, version, ctx -> writer.create(ctx, badPeriod)));
				// read back as the edit form sees it
				var edit = ActionWriter.editable(db, version, id[0]).orElseThrow();
				assertEquals(null, edit.reason());
				assertEquals(new java.util.HashSet<>(updated.conditions()), new java.util.HashSet<>(edit.input().conditions()));
				assertEquals(2, edit.input().operations().size());
			} finally {
				api.call("action.delete", Json.createArrayBuilder().add(String.valueOf(id[0])).build());
			}
			return null;
		});
	}

	private static long id(org.tamacat.sql.SqlOperations db, String sql) {
		return db.queryOne(PreparedSql.of(sql), r -> r.getLong(1)).orElseThrow();
	}

	/** The same definition as an API request (complete: every group, recipients always given). */
	static JsonObject json(ActionInput a, Long actionId, ZabbixVersion version) {
		JsonObjectBuilder b = Json.createObjectBuilder();
		if (actionId == null) {
			b.add("eventsource", 0);
		} else {
			b.add("actionid", String.valueOf(actionId));
		}
		b.add("name", a.name()).add("status", a.status()).add("esc_period", a.escPeriod())
				.add("pause_suppressed", a.pauseSuppressed() ? 1 : 0).add("notify_if_canceled", a.notifyIfCanceled() ? 1 : 0);
		if (version != ZabbixVersion.V6_0) {
			b.add("pause_symptoms", a.pauseSymptoms() ? 1 : 0);
		}
		JsonArrayBuilder conds = Json.createArrayBuilder();
		for (Condition c : a.conditions()) {
			JsonObjectBuilder cb = Json.createObjectBuilder().add("conditiontype", c.type()).add("operator", c.operator())
					.add("value", c.value());
			if (c.type() == 26) {
				cb.add("value2", c.value2());
			}
			conds.add(cb);
		}
		b.add("filter", Json.createObjectBuilder().add("evaltype", a.evalType()).add("conditions", conds));
		for (var g : Map.of("operations", 0, "recovery_operations", 1, "update_operations", 2).entrySet()) {
			JsonArrayBuilder ops = Json.createArrayBuilder();
			for (Operation op : a.group(g.getValue())) {
				JsonObjectBuilder ob = Json.createObjectBuilder().add("operationtype", op.type());
				if (g.getValue() == 0) {
					ob.add("esc_step_from", op.stepFrom()).add("esc_step_to", op.stepTo()).add("esc_period", op.escPeriod());
				}
				Message m = op.message();
				JsonObjectBuilder mb = Json.createObjectBuilder().add("default_msg", m.defaultMessage() ? 1 : 0);
				if (!m.defaultMessage()) {
					mb.add("subject", m.subject()).add("message", m.message());
				}
				if (op.type() == 0) {
					mb.add("mediatypeid", String.valueOf(m.mediaTypeId() == null ? 0 : m.mediaTypeId()));
					JsonArrayBuilder grps = Json.createArrayBuilder();
					m.userGroupIds().forEach(x -> grps.add(Json.createObjectBuilder().add("usrgrpid", String.valueOf(x))));
					JsonArrayBuilder usrs = Json.createArrayBuilder();
					m.userIds().forEach(x -> usrs.add(Json.createObjectBuilder().add("userid", String.valueOf(x))));
					ob.add("opmessage_grp", grps).add("opmessage_usr", usrs);
				}
				ob.add("opmessage", mb);
				ops.add(ob);
			}
			b.add(g.getKey(), ops);
		}
		return b.build();
	}
}
