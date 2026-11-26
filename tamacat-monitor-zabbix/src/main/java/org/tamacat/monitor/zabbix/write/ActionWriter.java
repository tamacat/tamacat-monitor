package org.tamacat.monitor.zabbix.write;

import static org.tamacat.monitor.zabbix.write.HostGroupSets.ordered;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.tamacat.dao.BindValue;
import org.tamacat.dao.Conditions;
import org.tamacat.dao.PreparedSql;
import org.tamacat.dao.SqlBuilder;
import org.tamacat.sql.SqlOperations;
import org.tamacat.monitor.common.ConflictException;
import org.tamacat.monitor.common.NotFoundException;
import org.tamacat.monitor.common.ValidationException;
import org.tamacat.monitor.zabbix.ZabbixVersion;

/**
 * Trigger action writes (T-407), equal to what the Zabbix API writes (golden-master tests on 6.0 / 7.0).
 * <p>
 * Supported: trigger actions (eventsource 0) with the evaluation types and/or, and, or; conditions on
 * host group, host, trigger severity, tag name and tag value; "send message" operations to user groups
 * and users (all or one media type, default or custom message), "notify all involved" as recovery and
 * update operations. Actions using anything else (custom formula, remote commands, operation conditions,
 * other condition types) are shown but not changed here.
 * <p>
 * An update replaces the whole definition, matching existing rows as the API does (CAction::update):
 * conditions by type and value (tag value: by tag name), operations by operation type in each group
 * (problem / recovery / update), recipients by user group / user; matched rows are updated in place,
 * the rest deleted or inserted. A message switched to the default message keeps its stored subject
 * and text, as with the API.
 */
public final class ActionWriter {

	public static final int GROUP_PROBLEM = 0;
	public static final int GROUP_RECOVERY = 1;
	public static final int GROUP_UPDATE = 2;
	/** 0 host group, 1 host, 4 trigger severity, 25 tag name, 26 tag value. */
	public static final Set<Integer> CONDITION_TYPES = Set.of(0, 1, 4, 25, 26);
	public static final int OP_MESSAGE = 0;
	public static final int OP_RECOVERY_ALL = 11;
	public static final int OP_UPDATE_ALL = 12;

	private static final Pattern TIME = Pattern.compile("([0-9]{1,7})([smhdw]?)");
	private static final int MIN_PERIOD = 60;
	private static final int MAX_PERIOD = 7 * 24 * 3600;

	/**
	 * @param type     condition type (see {@link #CONDITION_TYPES})
	 * @param operator 0 equals, 1 does not equal, 2 contains, 3 does not contain, 5 &gt;=, 6 &lt;=
	 * @param value    host group id, host id, severity 0-5, tag name, or tag value
	 * @param value2   tag name of a tag value condition, else ""
	 */
	public record Condition(int type, int operator, String value, String value2) {
	}

	/**
	 * @param mediaTypeId null for all media types
	 */
	public record Message(boolean defaultMessage, String subject, String message, Long mediaTypeId, List<Long> userGroupIds,
			List<Long> userIds) {
	}

	/**
	 * @param type       0 send message; 11 (recovery) / 12 (update) notify all involved
	 * @param stepFrom   escalation step from (problem operations only)
	 * @param stepTo     escalation step to, 0 = infinitely (problem operations only)
	 * @param escPeriod  step duration, "0" = the action's default (problem operations only)
	 */
	public record Operation(int type, int stepFrom, int stepTo, String escPeriod, Message message) {
	}

	/**
	 * @param evalType        0 and/or, 1 and, 2 or
	 * @param pauseSymptoms   7.0 and later; ignored on 6.0
	 */
	public record ActionInput(String name, int status, String escPeriod, boolean pauseSuppressed, boolean notifyIfCanceled,
			boolean pauseSymptoms, int evalType, List<Condition> conditions, List<Operation> operations,
			List<Operation> recoveryOperations, List<Operation> updateOperations) {

		List<Operation> group(int g) {
			return switch (g) {
				case GROUP_PROBLEM -> operations;
				case GROUP_RECOVERY -> recoveryOperations;
				default -> updateOperations;
			};
		}
	}

	// ---- create ----

	public long create(WriteContext ctx, ActionInput raw) {
		ActionInput in = validate(ctx, raw, null);
		long actionId = ctx.nextId("actions", "actionid");
		Map<String, Object> row = ordered("actionid", actionId, "name", in.name(), "eventsource", 0, "evaltype", in.evalType(),
				"status", in.status(), "esc_period", in.escPeriod(), "formula", "", "pause_suppressed", flag(in.pauseSuppressed()),
				"notify_if_canceled", flag(in.notifyIfCanceled()));
		if (!v60(ctx)) {
			row.put("pause_symptoms", flag(in.pauseSymptoms()));
		}
		ctx.insert("actions", "actionid", row);
		for (Condition c : in.conditions()) {
			insertCondition(ctx, actionId, c);
		}
		List<long[]> created = new ArrayList<>(); // operationid, group, index
		for (int g = 0; g <= 2; g++) {
			List<Operation> ops = in.group(g);
			for (int i = 0; i < ops.size(); i++) {
				created.add(new long[] { insertOperation(ctx, actionId, g, ops.get(i)), g, i });
			}
		}
		writeMessages(ctx, in, created, Map.of());
		return actionId;
	}

	// ---- update ----

	public void update(WriteContext ctx, long actionId, String expectedName, ActionInput raw) {
		Stored cur = load(ctx.db(), ctx.schema().version(), actionId).orElseThrow(() -> new NotFoundException("action " + actionId));
		cur.unsupported().ifPresent(key -> {
			throw new ConflictException(key);
		});
		if (!cur.name().equals(expectedName)) {
			throw new ConflictException("error.write.conflict");
		}
		ActionInput in = validate(ctx, raw, actionId);

		Map<String, Object> changes = new LinkedHashMap<>();
		putIfChanged(changes, "name", cur.name(), in.name());
		putIfChanged(changes, "status", cur.status(), in.status());
		putIfChanged(changes, "esc_period", cur.escPeriod(), in.escPeriod());
		putIfChanged(changes, "evaltype", cur.evalType(), in.evalType());
		putIfChanged(changes, "pause_suppressed", cur.pauseSuppressed(), flag(in.pauseSuppressed()));
		putIfChanged(changes, "notify_if_canceled", cur.notifyIfCanceled(), flag(in.notifyIfCanceled()));
		if (!v60(ctx)) {
			putIfChanged(changes, "pause_symptoms", cur.pauseSymptoms(), flag(in.pauseSymptoms()));
		}
		if (!changes.isEmpty()) {
			ctx.update("actions", "actionid", actionId, Map.of("name", expectedName), changes);
		}

		// conditions
		List<StoredCondition> left = new ArrayList<>(cur.conditions());
		List<Object[]> updates = new ArrayList<>();
		List<Condition> inserts = new ArrayList<>();
		for (Condition c : in.conditions()) {
			Optional<StoredCondition> m = left.stream().filter(s -> s.type() == c.type()
					&& (c.type() == 26 ? s.value2().equals(c.value2()) : s.value().equals(c.value()))).findFirst();
			if (m.isPresent()) {
				left.remove(m.get());
				updates.add(new Object[] { m.get(), c });
			} else {
				inserts.add(c);
			}
		}
		for (StoredCondition s : left) {
			ctx.delete("conditions", "conditionid", s.id(), Map.of("actionid", actionId));
		}
		for (Object[] u : updates) {
			StoredCondition s = (StoredCondition) u[0];
			Condition c = (Condition) u[1];
			Map<String, Object> ch = new LinkedHashMap<>();
			putIfChanged(ch, "operator", s.operator(), c.operator());
			putIfChanged(ch, "value", s.value(), c.value());
			putIfChanged(ch, "value2", s.value2(), c.value2());
			if (!ch.isEmpty()) {
				ctx.update("conditions", "conditionid", s.id(), Map.of("actionid", actionId), ch);
			}
		}
		for (Condition c : inserts) {
			insertCondition(ctx, actionId, c);
		}

		// operations
		Map<Long, StoredOperation> matched = new HashMap<>();
		List<long[]> placed = new ArrayList<>(); // operationid (or -1 = new), group, index
		List<StoredOperation> deleted = new ArrayList<>();
		for (int g = 0; g <= 2; g++) {
			final int group = g;
			List<StoredOperation> remaining = new ArrayList<>(cur.operations().stream().filter(o -> o.recovery() == group).toList());
			List<Operation> ops = in.group(g);
			for (int i = 0; i < ops.size(); i++) {
				Operation op = ops.get(i);
				Optional<StoredOperation> m = remaining.stream().filter(o -> o.type() == op.type()).findFirst();
				if (m.isPresent()) {
					remaining.remove(m.get());
					matched.put(m.get().id(), m.get());
					placed.add(new long[] { m.get().id(), g, i });
				} else {
					placed.add(new long[] { -1, g, i });
				}
			}
			deleted.addAll(remaining);
		}
		for (StoredOperation o : deleted) {
			for (long grp : o.groupRows().keySet()) {
				ctx.delete("opmessage_grp", "opmessage_grpid", grp, Map.of("operationid", o.id()));
			}
			for (long usr : o.userRows().keySet()) {
				ctx.delete("opmessage_usr", "opmessage_usrid", usr, Map.of("operationid", o.id()));
			}
			if (o.message() != null) {
				ctx.delete("opmessage", "operationid", o.id(), Map.of());
			}
			ctx.delete("operations", "operationid", o.id(), Map.of("actionid", actionId));
		}
		for (long[] p : placed) {
			if (p[0] < 0 || p[1] != GROUP_PROBLEM) {
				continue;
			}
			StoredOperation s = matched.get(p[0]);
			Operation op = in.group((int) p[1]).get((int) p[2]);
			Map<String, Object> ch = new LinkedHashMap<>();
			putIfChanged(ch, "esc_period", s.escPeriod(), op.escPeriod());
			putIfChanged(ch, "esc_step_from", s.stepFrom(), op.stepFrom());
			putIfChanged(ch, "esc_step_to", s.stepTo(), op.stepTo());
			if (!ch.isEmpty()) {
				ctx.update("operations", "operationid", s.id(), Map.of("actionid", actionId), ch);
			}
		}
		List<long[]> all = new ArrayList<>();
		for (long[] p : placed) {
			all.add(p[0] < 0 ? new long[] { insertOperation(ctx, actionId, (int) p[1], in.group((int) p[1]).get((int) p[2])), p[1], p[2] }
					: p);
		}
		writeMessages(ctx, in, all, matched);
	}

	/**
	 * opmessage rows (update changed fields of matched operations, insert for new ones), then recipients
	 * by difference: user groups of all operations first, then users, as the API does.
	 */
	private static void writeMessages(WriteContext ctx, ActionInput in, List<long[]> ops, Map<Long, StoredOperation> stored) {
		for (long[] p : ops) {
			Message m = in.group((int) p[1]).get((int) p[2]).message();
			StoredOperation s = stored.get(p[0]);
			if (s != null && s.message() != null) {
				StoredMessage cur = s.message();
				Map<String, Object> ch = new LinkedHashMap<>();
				putIfChanged(ch, "default_msg", cur.defaultMsg(), flag(m.defaultMessage()));
				if (!m.defaultMessage()) {
					putIfChanged(ch, "subject", cur.subject(), m.subject());
					putIfChanged(ch, "message", cur.message(), m.message());
				}
				if (!Objects.equals(cur.mediaTypeId(), m.mediaTypeId())) {
					ch.put("mediatypeid", m.mediaTypeId() == null ? BindValue.ofNull(Types.BIGINT) : m.mediaTypeId());
				}
				if (!ch.isEmpty()) {
					ctx.update("opmessage", "operationid", p[0], Map.of(), ch);
				}
			} else {
				Map<String, Object> row = ordered("operationid", p[0], "default_msg", flag(m.defaultMessage()), "subject",
						m.defaultMessage() ? "" : m.subject(), "message", m.defaultMessage() ? "" : m.message());
				if (m.mediaTypeId() != null) {
					row.put("mediatypeid", m.mediaTypeId());
				}
				ctx.insert("opmessage", "operationid", row);
			}
		}
		recipients(ctx, in, ops, stored, true);
		recipients(ctx, in, ops, stored, false);
	}

	private static void recipients(WriteContext ctx, ActionInput in, List<long[]> ops, Map<Long, StoredOperation> stored,
			boolean groups) {
		String table = groups ? "opmessage_grp" : "opmessage_usr";
		String idCol = groups ? "opmessage_grpid" : "opmessage_usrid";
		String refCol = groups ? "usrgrpid" : "userid";
		List<long[]> inserts = new ArrayList<>(); // operationid, ref
		for (long[] p : ops) {
			Operation op = in.group((int) p[1]).get((int) p[2]);
			if (op.type() != OP_MESSAGE) {
				continue;
			}
			List<Long> wanted = groups ? op.message().userGroupIds() : op.message().userIds();
			StoredOperation s = stored.get(p[0]);
			Map<Long, Long> current = new LinkedHashMap<>(); // ref -> row id
			if (s != null) {
				(groups ? s.groupRows() : s.userRows()).forEach((rowId, ref) -> current.put(ref, rowId));
			}
			for (long ref : wanted) {
				if (current.remove(ref) == null) {
					inserts.add(new long[] { p[0], ref });
				}
			}
			for (long rowId : current.values()) {
				ctx.delete(table, idCol, rowId, Map.of("operationid", p[0]));
			}
		}
		for (long[] i : inserts) {
			ctx.insert(table, idCol, ordered(idCol, ctx.nextId(table, idCol), "operationid", i[0], refCol, i[1]));
		}
	}

	private static void insertCondition(WriteContext ctx, long actionId, Condition c) {
		ctx.insert("conditions", "conditionid", ordered("conditionid", ctx.nextId("conditions", "conditionid"), "actionid", actionId,
				"conditiontype", c.type(), "operator", c.operator(), "value", c.value(), "value2", c.value2()));
	}

	private static long insertOperation(WriteContext ctx, long actionId, int group, Operation op) {
		long id = ctx.nextId("operations", "operationid");
		boolean problem = group == GROUP_PROBLEM;
		ctx.insert("operations", "operationid", ordered("operationid", id, "actionid", actionId, "operationtype", op.type(),
				"recovery", group, "esc_period", problem ? op.escPeriod() : "0", "esc_step_from", problem ? op.stepFrom() : 1,
				"esc_step_to", problem ? op.stepTo() : 1, "evaltype", 0));
		return id;
	}

	// ---- delete ----

	/** Deletes an action tamacat-monitor can edit (T-408): recipients, messages, operations, conditions, the action. */
	public void delete(WriteContext ctx, long actionId, String expectedName) {
		Stored cur = load(ctx.db(), ctx.schema().version(), actionId).orElseThrow(() -> new NotFoundException("action " + actionId));
		cur.unsupported().ifPresent(key -> {
			throw new ConflictException(key);
		});
		if (!cur.name().equals(expectedName)) {
			throw new ConflictException("error.write.conflict");
		}
		for (StoredOperation o : cur.operations()) {
			for (long grp : o.groupRows().keySet()) {
				ctx.delete("opmessage_grp", "opmessage_grpid", grp, Map.of("operationid", o.id()));
			}
			for (long usr : o.userRows().keySet()) {
				ctx.delete("opmessage_usr", "opmessage_usrid", usr, Map.of("operationid", o.id()));
			}
			ctx.delete("opmessage", "operationid", o.id(), Map.of());
		}
		for (StoredOperation o : cur.operations()) {
			ctx.delete("operations", "operationid", o.id(), Map.of("actionid", actionId));
		}
		for (StoredCondition c : cur.conditions()) {
			ctx.delete("conditions", "conditionid", c.id(), Map.of("actionid", actionId));
		}
		ctx.delete("actions", "actionid", actionId, Map.of("name", expectedName));
	}

	// ---- reading the stored definition ----

	record StoredCondition(long id, int type, int operator, String value, String value2) {
	}

	record StoredMessage(int defaultMsg, String subject, String message, Long mediaTypeId) {
	}

	/** groupRows / userRows: row id -> user group id / user id, in row id order. */
	record StoredOperation(long id, int type, int recovery, String escPeriod, int stepFrom, int stepTo, StoredMessage message,
			Map<Long, Long> groupRows, Map<Long, Long> userRows, boolean hasOtherDetails) {
	}

	record Stored(long id, String name, int eventSource, int status, String escPeriod, int evalType, int pauseSuppressed,
			int notifyIfCanceled, Integer pauseSymptoms, List<StoredCondition> conditions, List<StoredOperation> operations) {

		/** The message key when this action uses something tamacat-monitor does not edit. */
		Optional<String> unsupported() {
			if (eventSource != 0) {
				return Optional.of("error.action.eventSource");
			}
			if (evalType == 3 || conditions.stream().anyMatch(c -> !CONDITION_TYPES.contains(c.type()))) {
				return Optional.of("error.action.unsupported");
			}
			for (StoredOperation o : operations) {
				boolean ok = !o.hasOtherDetails() && o.message() != null && switch (o.recovery()) {
					case GROUP_PROBLEM -> o.type() == OP_MESSAGE;
					case GROUP_RECOVERY -> o.type() == OP_MESSAGE || o.type() == OP_RECOVERY_ALL;
					default -> o.type() == OP_MESSAGE || o.type() == OP_UPDATE_ALL;
				};
				if (!ok) {
					return Optional.of("error.action.unsupported");
				}
			}
			return Optional.empty();
		}

		ActionInput toInput() {
			List<List<Operation>> groups = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
			for (StoredOperation o : operations) {
				StoredMessage m = o.message();
				groups.get(o.recovery()).add(new Operation(o.type(), o.stepFrom(), o.stepTo(), o.escPeriod(),
						m == null ? null : new Message(m.defaultMsg() == 1, m.subject(), m.message(), m.mediaTypeId(),
								List.copyOf(o.groupRows().values()), List.copyOf(o.userRows().values()))));
			}
			return new ActionInput(name, status, escPeriod, pauseSuppressed == 1, notifyIfCanceled == 1,
					pauseSymptoms == null || pauseSymptoms == 1, evalType,
					conditions.stream().map(c -> new Condition(c.type(), c.operator(), c.value(), c.value2())).toList(),
					groups.get(0), groups.get(1), groups.get(2));
		}
	}

	/** The stored definition for the edit form, or empty when not found; {@code reason} is set when not editable. */
	public record Editable(ActionInput input, String reason) {
	}

	public static Optional<Editable> editable(SqlOperations db, ZabbixVersion version, long actionId) {
		return load(db, version, actionId).map(s -> new Editable(s.toInput(), s.unsupported().orElse(null)));
	}

	static Optional<Stored> load(SqlOperations db, ZabbixVersion version, long actionId) {
		record A(String name, int eventSource, int status, String escPeriod, int evalType, int ps, int nic, Integer sym) {
		}
		boolean v60 = version == ZabbixVersion.V6_0;
		Optional<A> a = db.queryOne(PreparedSql.of("select name, eventsource, status, esc_period, evaltype, pause_suppressed, "
				+ "notify_if_canceled" + (v60 ? "" : ", pause_symptoms") + " from actions where actionid = ?", actionId), r -> {
			Integer sym = v60 ? null : r.getInt("pause_symptoms");
			return new A(r.getString("name"), r.getInt("eventsource"), r.getInt("status"), r.getString("esc_period"),
					r.getInt("evaltype"), r.getInt("pause_suppressed"), r.getInt("notify_if_canceled"), sym);
		});
		if (a.isEmpty()) {
			return Optional.empty();
		}
		List<StoredCondition> conditions = db.queryList(PreparedSql.of("select conditionid, conditiontype, operator, value, value2 "
				+ "from conditions where actionid = ? order by conditionid", actionId),
				r -> new StoredCondition(r.getLong("conditionid"), r.getInt("conditiontype"), r.getInt("operator"),
						r.getString("value"), r.getString("value2")));
		record O(long id, int type, int recovery, String period, int from, int to) {
		}
		List<O> ops = db.queryList(PreparedSql.of("select operationid, operationtype, recovery, esc_period, esc_step_from, "
				+ "esc_step_to from operations where actionid = ? order by operationid", actionId),
				r -> new O(r.getLong("operationid"), r.getInt("operationtype"), r.getInt("recovery"), r.getString("esc_period"),
						r.getInt("esc_step_from"), r.getInt("esc_step_to")));
		List<StoredOperation> operations = new ArrayList<>();
		if (!ops.isEmpty()) {
			List<Long> ids = ops.stream().map(O::id).toList();
			Map<Long, StoredMessage> messages = new HashMap<>();
			db.queryList(SqlBuilder.of("select operationid, default_msg, subject, message, mediatypeid from opmessage")
					.where(Conditions.in("operationid", ids)).build(),
					r -> Map.entry(r.getLong("operationid"), new StoredMessage(r.getInt("default_msg"), r.getString("subject"),
							r.getString("message"), r.getNullableLong("mediatypeid"))))
					.forEach(e -> messages.put(e.getKey(), e.getValue()));
			Map<Long, Map<Long, Long>> grps = rows(db, "select opmessage_grpid, operationid, usrgrpid from opmessage_grp", ids);
			Map<Long, Map<Long, Long>> usrs = rows(db, "select opmessage_usrid, operationid, userid from opmessage_usr", ids);
			Set<Long> other = new LinkedHashSet<>();
			for (String t : List.of("opconditions", "opcommand", "opgroup", "optemplate", "opinventory")) {
				other.addAll(db.queryList(SqlBuilder.of("select operationid from " + t).where(Conditions.in("operationid", ids)).build(),
						r -> r.getLong(1)));
			}
			for (O o : ops) {
				operations.add(new StoredOperation(o.id(), o.type(), o.recovery(), o.period(), o.from(), o.to(), messages.get(o.id()),
						grps.getOrDefault(o.id(), Map.of()), usrs.getOrDefault(o.id(), Map.of()), other.contains(o.id())));
			}
		}
		A x = a.get();
		return Optional.of(new Stored(actionId, x.name(), x.eventSource(), x.status(), x.escPeriod(), x.evalType(), x.ps(), x.nic(),
				x.sym(), conditions, operations));
	}

	private static Map<Long, Map<Long, Long>> rows(SqlOperations db, String select, List<Long> operationIds) {
		Map<Long, Map<Long, Long>> out = new HashMap<>();
		db.queryList(SqlBuilder.of(select).where(Conditions.in("operationid", operationIds)).orderBy(org.tamacat.dao.Sort.asc(
				select.contains("opmessage_grpid") ? "opmessage_grpid" : "opmessage_usrid")).build(),
				r -> new long[] { r.getLong(1), r.getLong(2), r.getLong(3) })
				.forEach(v -> out.computeIfAbsent(v[1], k -> new LinkedHashMap<>()).put(v[0], v[2]));
		return out;
	}

	// ---- validation ----

	private static ActionInput validate(WriteContext ctx, ActionInput in, Long self) {
		String name = in.name() == null ? "" : in.name().strip();
		if (name.isEmpty() || name.length() > 255) {
			throw ValidationException.of("name", "error.action.name");
		}
		if (ctx.db().queryOne(PreparedSql.of("select actionid from actions where name = ?", name), r -> r.getLong(1))
				.filter(id -> !id.equals(self)).isPresent()) {
			throw new ConflictException("error.action.duplicate");
		}
		if (in.status() != 0 && in.status() != 1) {
			throw ValidationException.of("status", "error.action.status");
		}
		String esc = period(in.escPeriod(), false, "escPeriod");
		if (in.evalType() < 0 || in.evalType() > 2) {
			throw ValidationException.of("evalType", "error.action.evalType");
		}
		List<Condition> conditions = new ArrayList<>();
		for (Condition c : in.conditions() == null ? List.<Condition>of() : in.conditions()) {
			Condition v = condition(ctx, c);
			if (conditions.contains(v)) {
				throw ValidationException.of("conditions", "error.action.conditionDuplicate");
			}
			conditions.add(v);
		}
		List<Operation> problem = operations(ctx, in.operations(), GROUP_PROBLEM);
		List<Operation> recovery = operations(ctx, in.recoveryOperations(), GROUP_RECOVERY);
		List<Operation> update = operations(ctx, in.updateOperations(), GROUP_UPDATE);
		return new ActionInput(name, in.status(), esc, in.pauseSuppressed(), in.notifyIfCanceled(), in.pauseSymptoms(),
				in.evalType(), conditions, problem, recovery, update);
	}

	private static Condition condition(WriteContext ctx, Condition c) {
		String value = c.value() == null ? "" : c.value().strip();
		String value2 = "";
		Set<Integer> operators;
		switch (c.type()) {
			case 0, 1 -> {
				operators = Set.of(0, 1);
				long id = parseId(value);
				String sql = c.type() == 0 ? "select count(*) from hstgrp where groupid = ?"
						: "select count(*) from hosts where hostid = ? and status in (0, 1) and flags in (0, 4)";
				if (ctx.db().queryOne(PreparedSql.of(sql, id), r -> r.getInt(1)).orElse(0) == 0) {
					throw ValidationException.of("conditions", "error.action.conditionValue");
				}
				value = String.valueOf(id);
			}
			case 4 -> {
				operators = Set.of(0, 1, 5, 6);
				if (!value.matches("[0-5]")) {
					throw ValidationException.of("conditions", "error.action.conditionValue");
				}
			}
			case 25 -> {
				operators = Set.of(0, 1, 2, 3);
				if (value.isEmpty() || value.length() > 255) {
					throw ValidationException.of("conditions", "error.action.conditionValue");
				}
			}
			case 26 -> {
				operators = Set.of(0, 1, 2, 3);
				value2 = c.value2() == null ? "" : c.value2().strip();
				if (value2.isEmpty() || value2.length() > 255 || value.length() > 255) {
					throw ValidationException.of("conditions", "error.action.conditionValue");
				}
			}
			default -> throw ValidationException.of("conditions", "error.action.conditionType");
		}
		if (!operators.contains(c.operator())) {
			throw ValidationException.of("conditions", "error.action.conditionOperator");
		}
		return new Condition(c.type(), c.operator(), value, value2);
	}

	private static List<Operation> operations(WriteContext ctx, List<Operation> ops, int group) {
		List<Operation> out = new ArrayList<>();
		boolean notifyAll = false;
		for (Operation op : ops == null ? List.<Operation>of() : ops) {
			int allType = group == GROUP_RECOVERY ? OP_RECOVERY_ALL : group == GROUP_UPDATE ? OP_UPDATE_ALL : -1;
			if (op.type() != OP_MESSAGE && op.type() != allType) {
				throw ValidationException.of("operations", "error.action.operationType");
			}
			Message m = op.message();
			if (m == null) {
				throw ValidationException.of("operations", "error.action.message");
			}
			String subject = m.defaultMessage() ? "" : Objects.requireNonNullElse(m.subject(), "");
			String text = m.defaultMessage() ? "" : Objects.requireNonNullElse(m.message(), "");
			if (subject.length() > 255 || text.length() > 65535) {
				throw ValidationException.of("operations", "error.action.message");
			}
			Long media = m.mediaTypeId() == null || m.mediaTypeId() == 0 ? null : m.mediaTypeId();
			List<Long> groups = List.of();
			List<Long> users = List.of();
			if (op.type() == OP_MESSAGE) {
				if (media != null && count(ctx, "select count(*) from media_type where mediatypeid = ?", media) == 0) {
					throw ValidationException.of("operations", "error.action.mediaType");
				}
				groups = List.copyOf(new LinkedHashSet<>(Objects.requireNonNullElse(m.userGroupIds(), List.of())));
				users = List.copyOf(new LinkedHashSet<>(Objects.requireNonNullElse(m.userIds(), List.of())));
				if (groups.isEmpty() && users.isEmpty()) {
					throw ValidationException.of("operations", "error.action.recipients");
				}
				for (long g : groups) {
					if (count(ctx, "select count(*) from usrgrp where usrgrpid = ?", g) == 0) {
						throw ValidationException.of("operations", "error.action.recipients");
					}
				}
				for (long u : users) {
					if (count(ctx, "select count(*) from users where userid = ?", u) == 0) {
						throw ValidationException.of("operations", "error.action.recipients");
					}
				}
			} else {
				if (notifyAll) {
					throw ValidationException.of("operations", "error.action.operationType");
				}
				notifyAll = true;
				media = null;
			}
			int from = 1;
			int to = 1;
			String period = "0";
			if (group == GROUP_PROBLEM) {
				from = op.stepFrom();
				to = op.stepTo();
				if (from < 1 || from > 99999 || to < 0 || to > 99999 || (to != 0 && from > to)) {
					throw ValidationException.of("operations", "error.action.steps");
				}
				period = period(op.escPeriod() == null || op.escPeriod().isBlank() ? "0" : op.escPeriod(), true, "operations");
			}
			out.add(new Operation(op.type(), from, to, period, new Message(m.defaultMessage(), subject, text, media, groups, users)));
		}
		return out;
	}

	/** A duration of 1m to 1w (seconds or with a s/m/h/d/w suffix); "0" where {@code zeroAllowed}. */
	static String period(String value, boolean zeroAllowed, String field) {
		String v = value == null ? "" : value.strip();
		var m = TIME.matcher(v);
		if (!m.matches()) {
			throw ValidationException.of(field, "error.action.period");
		}
		long n = Long.parseLong(m.group(1));
		long seconds = n * switch (m.group(2)) {
			case "m" -> 60;
			case "h" -> 3600;
			case "d" -> 86400;
			case "w" -> 604800;
			default -> 1;
		};
		if (seconds == 0 && zeroAllowed && v.equals("0")) {
			return v;
		}
		if (seconds < MIN_PERIOD || seconds > MAX_PERIOD) {
			throw ValidationException.of(field, "error.action.period");
		}
		return v;
	}

	private static long parseId(String v) {
		try {
			return Long.parseLong(v);
		} catch (NumberFormatException e) {
			throw ValidationException.of("conditions", "error.action.conditionValue");
		}
	}

	private static int count(WriteContext ctx, String sql, long id) {
		return ctx.db().queryOne(PreparedSql.of(sql, id), r -> r.getInt(1)).orElse(0);
	}

	private static void putIfChanged(Map<String, Object> changes, String col, Object current, Object wanted) {
		if (!Objects.equals(current, wanted)) {
			changes.put(col, wanted);
		}
	}

	private static int flag(boolean b) {
		return b ? 1 : 0;
	}

	private static boolean v60(WriteContext ctx) {
		return ctx.schema().version() == ZabbixVersion.V6_0;
	}
}
