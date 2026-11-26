# 負荷試験（T-503）

2026-11-21 実施。手順とツールは `docker/loadtest/`。

## 条件

- 環境：Windows 11 の podman machine 上の検証用コンテナ。tamacat-monitor（tamacat-httpd 1.7 + Tomcat 11.0.29、Java 25）、Zabbix 7.0.32 + MySQL 8.4（`innodb_buffer_pool_size` は既定の 128 MB）。同じ PC 上で計測するため、絶対値より傾向を見る。
- データ（`load-data-7.0.sql`、ID 900000000000 以降に投入し、試験後に `cleanup.sql` で削除）：

| 対象 | 件数 |
|---|---|
| ホスト | 2,000（グループ `tmon-load`） |
| アイテム | 20,000（トラッパー。各 300 件の値） |
| `history_uint` | 6,000,000 |
| トリガー | 2,000 |
| `events` | 1,000,000（30 日分） |
| `problem` | 220,000（未解決 20,000） |
| `alerts` | 500,000（30 日分） |

- 負荷：`loadtest.py`、同時 10 セッション、各 API 100 リクエスト。

## 結果（キャッシュが温まった状態、単位 ms）

| API | 管理者 p50 / p95 | 閲覧者（2,000 ホストを許可）p50 / p95 |
|---|---|---|
| ダッシュボード（全サーバー） | 20 / 673 ※ | 16 / 248 ※ |
| ダッシュボード（サーバー別） | 134 / 205 | 171 / 257 |
| 障害（新しい順 100 件） | 122 / 144 | 200 / 243 |
| 障害（深刻度 4 以上） | 129 / 167 | 109 / 120 |
| アクションログ（31 日、100 件） | 31 / 43 | 100 / 111 |
| アクションログ（1 日、文字列検索） | 66 / 82 | 140 / 157 |
| ホスト検索 | 193 / 227 | 193 / 221 |
| アイテム（ホスト指定、最新値つき） | 24 / 36 | 23 / 32 |
| アイテム（文字列検索、最新値つき） | 176 / 192 | 193 / 205 |

※ 最初の集計まではキャッシュがなく、最大 0.8〜2.1 秒。以降は `TMON_DASHBOARD_CACHE_SECONDS`（既定 30 秒）の間キャッシュを返す。

エラーは 0 件。許可範囲が 2 グループだけの閲覧者では、すべて p95 30 ms 前後。

## 所見

- **履歴の検索は件数に依存しない**：最新値はアイテムごとに主キー `(itemid, clock, ns)` を逆順に 1 件読むため、`history_uint` 600 万件でも 1 ページ（最大 200 アイテム）が数十 ms。
- **アクションログは期間で絞る**：`alerts.clock` の索引を新しい順に読み、件数上限で打ち切る。31 日の上限（`AlertReader.MAX_PERIOD`）があるので全件走査にならない。
- **スコープ条件**：許可グループのトリガー集合を MySQL が一度だけ実体化し、各行は索引で照合する（`EXPLAIN ANALYZE` で 31 日のアクションログが 35 ms）。
- **キャッシュが冷えた状態は遅い**：データ投入直後（バッファプールに載っていない状態）では、2,000 ホストを許可した閲覧者のアクションログ（31 日）が p95 12 秒、最大 20 秒だった。同じクエリが温まった後は約 0.1 秒。Zabbix DB の `innodb_buffer_pool_size` を、よく使う表（`alerts`、`events`、`problem`、`items`、`functions`、`hosts_groups`）が載る大きさにすることを推奨する。tamacat-monitor 側は `TMON_ZABBIX_QUERY_TIMEOUT`（30 秒）で打ち切り、横断検索は `TMON_ZABBIX_SERVER_TIMEOUT`（20 秒）を超えたサーバーだけをエラーにする。

## 再実行の手順

```bash
podman exec -i zabbix70-db mysql -uroot -p<root> zabbix < docker/loadtest/load-data-7.0.sql   # 1 分ほど
python -I docker/loadtest/loadtest.py http://127.0.0.1:8080/monitor admin <パスワードのファイル> 10 100
podman exec -i zabbix70-db mysql -uroot -p<root> zabbix < docker/loadtest/cleanup.sql
```

**使い捨ての検証用 DB にだけ使うこと。** データは `ids` を更新せずに大きな ID で投入する。
