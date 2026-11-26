package org.tamacat.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.tamacat.dao.exception.InvalidParameterException;
import org.tamacat.sql.IdentifierRules;
import org.tamacat.sql.LikeEscape;
import org.tamacat.sql.LikeMode;
import org.tamacat.sql.dialect.SqlDialect;

class SqlBuildingTest {

	@Test
	void placeholdersInsideQuotesAndCommentsAreIgnored() {
		assertEquals(1, PlaceholderScanner.countPlaceholders("select '?' from t where a = ?"));
		assertEquals(1, PlaceholderScanner.countPlaceholders("select \"a?\", `b?` from t where a = ?"));
		assertEquals(1, PlaceholderScanner.countPlaceholders("select 1 -- ?\n from t where a = ?"));
		assertEquals(1, PlaceholderScanner.countPlaceholders("select /* ? */ 1 from t where a = ?"));
		assertEquals(0, PlaceholderScanner.countPlaceholders("select 'it''s ?' from t"));
		assertEquals(-1, PlaceholderScanner.countPlaceholders("select 'open"));
		assertEquals(-1, PlaceholderScanner.countPlaceholders("select /* open"));
	}

	@Test
	void placeholderCountMustMatchValues() {
		assertThrows(InvalidParameterException.class, () -> PreparedSql.of("select * from t where a = ?"));
		assertThrows(InvalidParameterException.class, () -> PreparedSql.of("select * from t", 1L));
		assertEquals(2, PreparedSql.of("select * from t where a = ? and b = ?", 1L, "x").values().size());
	}

	@Test
	void nullNeedsExplicitType() {
		assertThrows(InvalidParameterException.class, () -> PreparedSql.of("select ?", (Object) null));
		PreparedSql sql = PreparedSql.of("select ?", BindValue.ofNull(Types.BIGINT));
		assertTrue(sql.values().get(0).isNull());
	}

	@Test
	void bindValuesAreTypedAndNeverPrinted() {
		assertEquals(Types.BIGINT, BindValue.from(10L).sqlType());
		assertEquals(Types.INTEGER, BindValue.from(10).sqlType());
		assertEquals(Types.VARCHAR, BindValue.from("secret").sqlType());
		assertFalse(BindValue.from("secret").toString().contains("secret"));
		assertFalse(PreparedSql.of("select ?", "secret").toString().contains("secret"));
		assertThrows(InvalidParameterException.class, () -> BindValue.from(new Object()));
	}

	@Test
	void identifiersAreAllowListed() {
		assertEquals("h.hostid", IdentifierRules.validate("h.hostid"));
		assertEquals("hosts", IdentifierRules.validate("hosts"));
		assertThrows(InvalidParameterException.class, () -> IdentifierRules.validate("hostid; drop table x"));
		assertThrows(InvalidParameterException.class, () -> IdentifierRules.validate("name desc"));
		assertThrows(InvalidParameterException.class, () -> IdentifierRules.validate("a.b.c"));
		assertThrows(InvalidParameterException.class, () -> IdentifierRules.validate("1abc"));
		assertThrows(InvalidParameterException.class, () -> IdentifierRules.validate(null));
		assertThrows(InvalidParameterException.class, () -> Conditions.eq("x' or '1'='1", 1));
	}

	@Test
	void likePatternsEscapeWildcards() {
		assertEquals("50!%!_off!!", LikeEscape.escape("50%_off!"));
		assertEquals("%web!_01%", LikeEscape.pattern("web_01", LikeMode.CONTAINS));
		assertEquals("web%", LikeEscape.pattern("web", LikeMode.PREFIX));
		assertEquals("%web", LikeEscape.pattern("web", LikeMode.SUFFIX));
		assertEquals("", LikeEscape.pattern(null, LikeMode.EXACT));

		Param p = Conditions.likeIgnoreCase("h.host", "Web", LikeMode.PREFIX);
		assertEquals("lower(h.host) like ? escape '!'", p.sql());
		assertEquals("web%", p.values().get(0).value());
	}

	@Test
	void inListHandlesEmptyCollections() {
		assertEquals("1=0", Conditions.in("hostid", List.of()).sql());
		assertEquals("1=1", Conditions.notIn("hostid", List.of()).sql());
		Param p = Conditions.in("hostid", List.of(1L, 2L, 3L));
		assertEquals("hostid in (?,?,?)", p.sql());
		assertEquals(3, p.values().size());
	}

	@Test
	void conditionsCombine() {
		Param p = Conditions.eq("a", 1).and(Conditions.eq("b", 2).or(Conditions.eq("c", 3)));
		assertEquals("a = ? and (b = ? or c = ?)", p.sql());
		assertEquals("not (a = ?)", Conditions.eq("a", 1).not().sql());
		assertTrue(Param.allOf((Param) null, null).isEmpty());
	}

	@Test
	void builderSkipsNullConditionsAndPaginates() {
		PreparedSql sql = SqlBuilder.of("select hostid from hosts")
				.where(Conditions.eq("status", 0), null, Conditions.like("host", "a", LikeMode.PREFIX))
				.orderBy(Sort.asc("host"))
				.build(SqlDialect.MYSQL, new Page(20, 10));
		assertEquals("select hostid from hosts where status = ? and host like ? escape '!' order by host asc limit ? offset ?",
				sql.sql());
		assertEquals(4, sql.values().size());
		assertEquals(10, sql.values().get(2).value());
		assertEquals(20, sql.values().get(3).value());

		assertEquals("select 1", SqlBuilder.of("select 1").where((Param) null).build().sql());
		assertThrows(InvalidParameterException.class, () -> SqlBuilder.of("select ?"));
	}

	@Test
	void sortFromRequestUsesAllowList() {
		Map<String, String> allowed = Map.of("name", "h.name", "status", "h.status");
		assertEquals("order by h.name desc", Sort.fromRequest("name", "DESC", allowed).orElseThrow().toSql());
		assertEquals("order by h.status asc", Sort.fromRequest("status", "x", allowed).orElseThrow().toSql());
		assertTrue(Sort.fromRequest("h.name;drop", "asc", allowed).isEmpty());
		assertTrue(Sort.fromRequest(null, "asc", allowed).isEmpty());
	}

	@Test
	void pageLimitsAreBounded() {
		assertThrows(InvalidParameterException.class, () -> new Page(0, 0));
		assertThrows(InvalidParameterException.class, () -> new Page(-1, 10));
		assertThrows(InvalidParameterException.class, () -> new Page(0, Page.MAX_LIMIT + 1));
		assertEquals(new Page(40, 20), Page.ofPageNumber(3, 20));
	}

	@Test
	void dialectFromJdbcUrl() {
		assertEquals(SqlDialect.MYSQL, SqlDialect.fromJdbcUrl("jdbc:mysql://db:3306/zabbix"));
		assertEquals(SqlDialect.POSTGRESQL, SqlDialect.fromJdbcUrl("jdbc:postgresql://db/zabbix"));
		assertThrows(InvalidParameterException.class, () -> SqlDialect.fromJdbcUrl("jdbc:oracle:thin:@x"));
	}
}
