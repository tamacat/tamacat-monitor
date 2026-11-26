# tamacat-monitor-dao

tamacat-dao 2.0（https://github.com/tamacat/tamacat-dao/tree/v2.0）を取り込み、改良したもの。
パッケージ名（`org.tamacat.dao` / `org.tamacat.sql`）は、本体へ戻す選択肢（ISSUES.md Q-05）を残すため維持している。

依存は `slf4j-api` のみ（テストは JUnit / H2 / tamacat-logging）。Tomcat・Servlet API には依存しない。

## 使い方

```java
ConnectionPool pool = new ConnectionPool(
        PoolConfig.builder("zabbix-01").minIdle(1).maxSize(10).build(),
        new DriverConnectionFactory("com.mysql.cj.jdbc.Driver", url, user, () -> password, null));
Database db = new Database("zabbix-01", pool, SqlDialect.fromJdbcUrl(url));

PreparedSql sql = SqlBuilder.of("select h.hostid, h.host from hosts h")
        .where(Conditions.eq("h.status", 0),
               Conditions.likeIgnoreCase("h.host", keyword, LikeMode.CONTAINS))
        .orderBy(Sort.asc("h.host"))
        .build(db.dialect(), Page.first(50));
List<Host> hosts = db.queryList(sql, r -> new Host(r.getLong("hostid"), r.getString("host")));

db.inTransaction(tx -> {
    tx.update(PreparedSql.of("update hosts set status = ? where hostid = ?", 1, hostId));
    return null;
});
```

## tamacat-dao 2.0 からの変更

| 区分 | 2.0 | tamacat-monitor-dao | 理由 |
|---|---|---|---|
| SQL 組み立て | 文字列直結の経路（`where(String)`、`Dao#param`、`PreparedSql.ofLiteral`）と bind の経路が併存 | **bind のみ**。`PreparedSql` / `Param` / `SqlBuilder` は生成時にプレースホルダ数と値の数を検証 | SQL インジェクションの経路をなくす |
| バインド | すべて `setString` | **型付き**（`setLong` / `setTimestamp` など） | PostgreSQL は bigint 列と varchar の比較を拒否する |
| 識別子 | 危険文字の拒否（ブラックリスト） | `IdentifierRules`：`name` / `alias.name` の許可リスト。ソートは `Sort.fromRequest` の対応表経由のみ | 許可リスト方式のほうが漏れがない |
| LIKE | 5 つの候補からエスケープ文字を選ぶ（全部含まれると未エスケープ） | 固定の `!` でエスケープし、`!` 自身もエスケープ | 未エスケープの抜け道をなくす |
| 接続管理 | `DBAccessManager` / `ConnectionManager` の static 名前付きレジストリ、ThreadLocal の接続、`db.xml`（DI） | `Database`（DataSource + 方言）を明示的に生成。`Session` を引数で渡す | Zabbix サーバーを実行時に追加・削除する。仮想スレッドでも同じように動く |
| トランザクション | static な `Transaction` と `TransactionStateManager` | `Database#inTransaction(tx -> ...)`：正常終了でコミット、例外でロールバック | 終了処理の漏れをなくす |
| プール | `StackObjectPool`（検証は null チェックのみ） | `ConnectionPool`：公平な Semaphore、`isValid` による検証、最大寿命・アイドルタイムアウト、返却時の状態リセット、閉じ忘れた Statement の close、リーク検出、統計 | 長時間運用での接続切れや状態の持ち越しを防ぐ |
| 方言 | MySQL、Oracle | MySQL、PostgreSQL（ページングはバインド） | 要件 |
| 結果の受け取り | リフレクションによる ORM（`MapBasedORMappingBean`） | `RowMapper`（record などへ明示的に詰め替える） | Zabbix のテーブルはバージョンで異なり、明示的な対応付けのほうが追いやすい |
| ログ | tamacat-core の `Log` | SLF4J（tamacat-logging）。SQL 文だけを出し、値は出さない | 秘密情報をログに出さない |
| 依存 | tamacat-core、javax.json、JNDI | slf4j-api のみ | 依存を最小化する |

削除したもの：`DaoAdapter` / `DaoFactory` / ORM / `JSONUtils` / `BlobUtils` / Oracle 方言 / mock パッケージ / DI 設定。
