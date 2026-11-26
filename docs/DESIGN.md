# tamacat-monitor 設計書

tamacat-monitor — 複数バージョンの Zabbix サーバーを統合管理する Java Web アプリケーション。

- リポジトリ: `tamacat-monitor`
- ベースパッケージ: `org.tamacat.monitor`
- ステータス: P4 実装中（v0.1 / 2026-11-21）

---

## 1. 目的とスコープ

### 1.1 目的
- 複数の Zabbix サーバー（6.0 / 7.0 / 8.0）を 1 つの Web UI から設定・検索・監視状況確認できるようにする。
- ユーザーと権限は Zabbix ではなく統合管理 DB で一元管理する。
- 長期運用を前提に、依存ライブラリを最小化し脆弱性リスクを抑える。

### 1.2 対象機能
| 区分 | 機能 |
|---|---|
| 監視設定 | ホストグループ / ホスト / アイテムの検索・作成・更新・削除 |
| アクション | アクションの検索・作成・更新・削除、アクションログ（`alerts`）の確認 |
| ダッシュボード | 管理者向け：全体 / Zabbix サーバー単位。一般ユーザー向け：許可ホストグループ範囲 |
| ユーザー管理 | 統合管理 DB でのユーザー・ロール・スコープ管理、認証 |
| i18n | 英語 / 日本語 |

### 1.3 対象外（当面）
- Zabbix 5.0 以前
- テンプレートのリンク / リンク解除（参照のみ。将来フェーズで検討）
- Zabbix の使わない機能（マップ、スクリーン、レポート、Web シナリオ編集など）
- Zabbix ユーザー / メディアの管理（通知先は Zabbix 既存のものを参照選択）

---

## 2. 技術スタック

| 層 | 採用 | 備考 |
|---|---|---|
| 言語 | Java 25 | 仮想スレッド、record、sealed を活用 |
| Web/AP | tamacat-httpd 1.7（1.7.0-b20261120）+ Tomcat Embedded 11.0.29 | Jakarta Servlet 6.1 / JSP 4.0。WAR を `webapps/` に配置 |
| View | JSP + JSTL（Jakarta Standard Tag Library） | 画面シェル、認証、i18n 初期化 |
| UI | Cloudscape Design System（React） | 画面ごとの小さなバンドル（アイランド）を JSP に埋め込む |
| JSON | Jakarta JSON-P（Eclipse Parsson） | 依存なしの小規模実装 |
| DB アクセス | tamacat-dao 2.0 改良版（本リポジトリに取り込み） | bind 専用、MySQL / PostgreSQL 方言 |
| コネクションプール | 独自実装（`tamacat-monitor-dao` 内） | Tomcat に依存しない（3.2 参照） |
| ロギング | tamacat-logging 1.0.0 | 標準出力のみ、text / ECS JSON |
| JDBC | MySQL Connector/J、PostgreSQL JDBC（オプション） | |
| 暗号 / ハッシュ / HTTP | JDK 標準 | PBKDF2、AES-GCM、HMAC |
| フロントビルド | npm + esbuild（ビルド時のみ） | lockfile 固定、`npm audit` を CI で実行 |

**使わないもの**: Spring、PHP、ORM（JPA/Hibernate）、Flyway、ロギングフレームワーク（Logback/Log4j）。

---

## 3. 全体アーキテクチャ

```
[Browser]  Cloudscape (React islands) + JSP
    │ HTTPS
[tamacat-httpd 1.7 + Tomcat 11 Embed]   ※同一プロセス
    └ monitor.war
        ├ web     : Servlet / JSP / Filter（認証, CSRF, i18n, セキュリティヘッダ, JSON API）
        ├ service : ユースケース, 権限スコープ適用, 監査記録
        ├ zabbix  : ZabbixGateway
        │    ├ reader : DB 直接参照（Version × Dialect）
        │    └ writer : DB 直接更新（Version × Dialect）
        ├ store   : 統合管理 DB リポジトリ
        └ dao     : tamacat-dao 改良版
    │                         │
[統合管理 DB: MySQL]   [Zabbix DB 6.0/7.0/8.0: MySQL（将来 PostgreSQL）]
```

### 3.1 Maven モジュール構成
| モジュール | 役割 |
|---|---|
| `tamacat-monitor-dao` | tamacat-dao 2.0 改良版（bind 専用 SQL、方言、トランザクション、コネクションプール）。本体へ還元するかは後で決める |
| `tamacat-monitor-core` | ドメインモデル（record）、サービス、権限モデル、統合管理 DB リポジトリ |
| `tamacat-monitor-zabbix` | Zabbix DB アクセス（バージョン差吸収、Reader / Writer、ID 採番）、サーバー登録。`tamacat-monitor-core` に依存 |
| `tamacat-monitor-admin` | Servlet / JSP / Filter / JSON API。WAR を生成 |
| `tamacat-monitor-ui` | Cloudscape React バンドル（npm / esbuild、TypeScript で型チェック）。`dist/static` を WAR に同梱。`mvn -Dskip.ui` で npm を省略 |
| `docker/` | tamacat-httpd イメージ、統合 DB、検証用 Zabbix 6.0 / 7.0 の compose |

**Tomcat への非依存**: `tamacat-monitor-dao` / `tamacat-monitor-core` / `tamacat-monitor-zabbix` は Tomcat にも Servlet API にも依存しない。`tamacat-monitor-admin` も Jakarta Servlet / JSP / JSTL の標準 API（`provided` スコープ）だけを使い、Tomcat 固有のクラスは参照しない。

**Tomcat Secure Embed の制約**: tamacat-httpd 1.7 の Tomcat はコンテナ管理セキュリティ（Realm / Authenticator）が削除されており、web.xml に `<security-constraint>` があるとコンテキストが起動しない。認証・認可はアプリの Filter で実装する（6 章）。

**tamacat-httpd 1.7 の Host ヘッダ書き換え**: 外から見たポートと httpd の待ち受けポートが違うと、Tomcat へ転送する Host ヘッダが壊れる（`127.0.0.1:8080:18090`）。Tomcat はこれを 400 で拒否する。当面はコンテナ内の待ち受けポートを 8080 にし、公開ポートも 8080 にそろえて回避する（ISSUES.md Q-07）。

**WAR に含めるもの / 含めないもの**: slf4j-api と tamacat-logging は tamacat-httpd 側のクラスパスにあるため `provided`（WAR に入れると SLF4J が 2 つになる）。MySQL Connector/J の `protobuf-java`（X DevAPI 用）と、Tomcat が持つ `jakarta.el-api` は除外する。

### 3.2 コネクションプール（独自実装）
DataSource ごと（統合 DB、各 Zabbix サーバーの参照用 / 更新用）に 1 つのプールを持つ。

| 項目 | 方針 |
|---|---|
| 実装 | `org.tamacat.sql.pool.ConnectionPool`（`tamacat-monitor-dao`） |
| API | `javax.sql.DataSource` を実装し、返す `Connection` はプロキシで包んで `close()` でプールへ返却 |
| ドライバ | `DriverManager` を使わず Driver を直接生成する（WAR 内のドライバでも登録不要、アンデプロイ時のリークなし） |
| サイズ | 最小アイドル数 / 最大数。取得待ちは `Semaphore` + タイムアウト（仮想スレッドで待てる設計） |
| 寿命 | 最大寿命（max lifetime）とアイドルタイムアウトで定期的に入れ替える |
| 検証 | 貸し出し前に、一定時間使われていない接続は `Connection.isValid(timeout)` で確認 |
| 返却時の状態リセット | 未コミットはロールバックし、autoCommit / readOnly / 分離レベル / カタログ・スキーマを初期値に戻す |
| リーク検出 | 貸し出し時間が閾値を超えたら、取得箇所のスタックを警告ログに出す |
| 終了処理 | サーバー登録の削除・変更時やアプリ停止時に、プール単位で閉じる |
| 監視 | 使用中 / アイドル / 待ち数を `/health` とログで確認できるようにする |
| 秘密情報 | パスワードは接続生成時だけ復号し、プールには保持しない |

---

## 4. Zabbix 連携方式

### 4.1 結論
**参照・更新ともに Zabbix DB へ直接アクセスする。** Zabbix API（PHP フロントエンド）には本番では依存しない。
API は、テストで正解データを作る用途（4.6）に限って使う。

### 4.2 バージョン差の吸収（Version × Dialect）
```java
sealed interface ZabbixSchema permits Schema60, Schema70, Schema80 { ... }
sealed interface SqlDialect   permits MySqlDialect, PostgreSqlDialect { ... }

ZabbixGateway gw = ZabbixGatewayFactory.create(server);  // dbversion 検出 + 方言判定
```
- `dbversion.mandatory` / `optional` でバージョンを判定する。
- 共通部分は基底クラスに置き、差分だけをバージョンごとに上書きする。
- **対応表にない `dbversion` を検出したら、そのサーバーは自動的に参照専用に切り替える。**

主な差分（公式コンテナ 6.0.49 / 7.0.32 / 8.0.0 + MySQL で 2026-11-21 に確認）:

| 差分 | 6.0 | 7.0 | 8.0 |
|---|---|---|---|
| `dbversion`（mandatory / optional） | 6000000 / 6000055 | 7000000 / 7000030 | 8000000 / 8000000 |
| テーブル数 | 173 | 203 | 230 |
| テンプレートグループ分離（`hstgrp.type`） | なし | あり | あり |
| プロキシ | `hosts.proxy_hostid` | `proxy` テーブル、`hosts.proxyid` / `proxy_groupid` | 7.0 と同じ |
| `changelog` による差分同期 | なし（定期全同期） | あり（DB トリガー 65 個） | あり |
| DB トリガー数 | 4 | 65 | 91 |
| `ids`（ID 採番） | あり。インストール直後は空 | あり。インストール直後は空 | あり（同じ方式） |
| アイテム単位のタイムアウト | なし | あり | あり |
| 全体設定 | `config`（1 行） | `config`（1 行） | `settings`（名前と値。既定値の項目は行がない） |
| 削除時の `housekeeper` 行 | フロントエンドが書く（`tablename` / `field` / `value`） | 同左 | DB トリガー（`items_housekeeping` / `triggers_housekeeping`）が書く（`object` / `objectid`） |
| テンプレートキャッシュ | なし | なし | `host_template_cache` / `item_template_cache`（新しいホスト・アイテムに行が必要） |
| 新しい TEXT 列（既定値なし） | — | — | `hosts.readme`、`items.query`（API は `{}`） |
| トリガーの実行時情報 | `triggers.value` など | 同左 | `trigger_rtdata` に移動 |
| マップのリンクからのアイテム参照 | なし | なし | `sysmaps_links.itemid`（削除の連鎖なし） |
| 新規トラッパーアイテムの許可ホスト | 空 | 空 | API が `{$TRAPPER.ALLOWED_HOSTS}` を入れる |
| ホストインターフェースの API | `hostinterface.*` | 同左 | 廃止（`host.update` の `interfaces`）。DB の書き込みは同じ |

`ids` はインストール直後は空で、行は初回採番時に作られる。P4 の採番（T-402）は「行がなければ `max(id)` から作る」処理も Zabbix と同じ方式に合わせる必要がある。

### 4.3 参照の方針
- Zabbix DB には参照用 / 更新用の 2 つの DB ユーザーを使い分ける。参照は可能ならレプリカに向ける。
- `history*` は生データの全走査を禁止する。`trends*`、`problem`、`events`、`alerts` を中心に使い、件数上限と期間指定を必須にする。
- 全体ダッシュボードはサーバーごとに仮想スレッドで並列収集する。タイムアウトを設け、障害サーバーがあっても全体の表示は継続する。結果は一定時間キャッシュする。
- 実装（P2）：`ZabbixQueryService` が、ユーザーがアクセスできるサーバー（有効・接続済み・対応バージョン）だけを対象に、仮想スレッドで並列に Reader を実行する。サーバーごとのタイムアウトは `TMON_ZABBIX_SERVER_TIMEOUT`（既定 20 秒）。失敗したサーバーは結果に `error` として返し、ほかのサーバーの結果は表示する。
- Reader：ホストグループ・ホスト・アイテム・障害・アクションログ・アクション。ホストはステータス 0 / 1 かつ flags 0 / 4（テンプレート・プロキシ・プロトタイプを除外）。
- アイテムの最新値：表示ページ分のアイテムについて、値の型ごとの履歴テーブル（`history`、`history_uint`、…）から主キー（itemid, clock, ns）で 1 件ずつ取る。1 ページは最大 200 件。バイナリ（7.0 の `history_bin`）は表示しない。
- アクションログ：期間を必須にし（最大 31 日）、`alerts.clock` の範囲で検索する。
- ダッシュボード（P3）：`DashboardService` がサーバーごとの集計（ホスト・障害・アイテム）を並列に取得する。集計はサーバーとアクセス範囲の組ごとにメモリへ `TMON_DASHBOARD_CACHE_SECONDS`（既定 30 秒）キャッシュし、多数の利用者が画面を開いても Zabbix DB への問い合わせが増えないようにする。
- 範囲を絞らない（管理者向けの）集計は、成功するたびに統合管理 DB の `dashboard_cache` に保存する。サーバーに接続できない・時間内に応答しないときは、管理者にだけ「最終取得時点の値」として表示し、全体の合計には含めない。
- 表現：数値はタイル（ラベルと大きな数字）、深刻度はアイコンとラベル付きの状態表示で示し、色だけで区別しない。独自の配色を持つグラフは使わない。

### 4.4 更新の安全策
- **書き込み用の DB ユーザーには、触るテーブルだけを GRANT する。**
- 1 操作を 1 トランザクションにし、ロック取得順序を固定する。
- 楽観的な競合検出：更新前の値を比較し、Zabbix フロントエンドなどで変更されていたら中止する。
- 実行前のプレビュー（dry-run）で、変更されるテーブルと行の差分を表示する。
- すべての更新を統合管理 DB の `audit_log` に記録する（Zabbix の `auditlog` には書かない）。

### 4.5 再現が必要な Zabbix の内部処理
| 項目 | 内容 | 難易度 |
|---|---|---|
| ID 採番 | Zabbix と同じ方式（`ids` テーブルを行ロックして `nextid` を進める）に合わせる。バージョンごとの実装を確認する | 中 |
| 設定の反映 | 6.4 以降は `changelog` トリガーが直接書き込みも拾う。6.0 は `CacheUpdateFrequency` の周期で反映 | 低 |
| ホストグループ | `hstgrp`（6.2 以降は `type` の考慮） | 低 |
| ホスト | `hosts`、`hosts_groups`、`interface`、`host_tag`、`host_inventory` | 中 |
| アイテム | `items`（6.0 は `name_upper`）、`item_rtdata`、`item_tag`、7.0 は `item_rtname`。キー / 更新間隔の書式チェックを自前実装。保存前処理（`item_preproc`）は対象外（実装済み: エージェント / アクティブ / トラッパー） | 中 |
| アクション | `actions`、`conditions`、`operations`、`opmessage`、`opmessage_grp`、`opmessage_usr`。トリガーアクションのメッセージ送信まで（実装済み）。`opcommand*`・`opconditions` は対象外 | 中 |
| 削除 | 依存トリガーの削除、`housekeeper` 登録、LLD 子要素の扱い | 高 |
| テンプレートのリンク | **対象外（当面）**。テンプレート由来のアイテム（`templateid` あり）は編集不可として扱う | — |

### 4.5.1 実装（P4：ホストグループまで）
- `IdAllocator`：`ids(table_name, field_name, nextid)` の `nextid` は**最後に払い出した ID**（6.0.49 / 7.0.32 の API で確認：max 19 → 新しい ID 20、nextid=20）。`SELECT ... FOR UPDATE` で行をロックし、書き込みと同じトランザクションで採番する。行がなければ `max(id)` から作る（同時に作られたら再試行）。PostgreSQL ではセーブポイントが必要（T-502）。
- `WriteContext`：insert / update / delete をすべて記録し、プレビューと監査ログに使う。update / delete は利用者が見た値を条件に含め、変わっていれば `error.write.conflict`。
- `ZabbixWriteService`：書き込み可能なサーバー（更新用アカウントあり、参照専用でない、接続済み、6.0 / 7.0）だけ。更新用アカウントのプールは参照用と別。プレビューは同じ処理を実行してロールバックする。成功・失敗とも統合管理 DB の監査ログに変更行を記録する。
- ホストグループ：作成は `hstgrp` 1 行（6.0 は internal=0、7.0 は type=0、flags=0、uuid は 32 桁の 16 進数）。7.0 の `changelog` にはホストグループの変更は記録されない。削除はホストが所属せず、スクリプト・アクション（条件・実行内容）・相関・ディスカバリ・ホストプロトタイプ・メンテナンス・`hgset_group`（7.0）から参照されていないグループだけ。
- 権限：ホストグループの変更はサーバー管理者以上。
- ホスト（T-405）：作成は `hosts`（指定しない列は DB の既定値のまま。API と同じ）、`hosts_groups`、`interface`、`host_tag`。更新は変わった列だけで、所属グループとタグは差分（削除と追加）。`name_upper` は表示名の大文字。
- 7.0 の権限キャッシュ：所属グループの組み合わせ（`hgset`、ハッシュは ID を数値順に `|` で連結した SHA-256）を、同じハッシュの最小 ID で再利用し、なければ作る。作るときは各ユーザーグループの組み合わせ（`ugset`）について、権限（`rights`）を「拒否が 1 つでもあれば拒否、なければ最大」で求め、拒否以外を `permission` に書く。使われなくなった組み合わせは消さない（Zabbix と同じ）。7.0 フロントエンドの `CHostGeneral::updateHgSets` と同じ規則。
- ホストの権限：サーバー管理者は全ホスト。オペレーターは変更前と変更後の所属グループがすべて許可範囲内のときだけ（他チームのグループへの移動・他チームのグループからの取り出しを防ぐ）。閲覧者は不可。
- 直接書き込んだホストは、Zabbix サーバーの設定キャッシュに取り込まれ、監視対象になることを確認済み（7.0 は `changelog` のトリガー経由）。
- テンプレートのリンク（T-601）：`hosts_templates` に行を追加し、テンプレートのアイテム（`items` の全列を複製、`templateid`・ホスト・エージェントのインターフェースを設定、`uuid` は空）、`item_rtdata`、7.0 以降 `item_rtname`、`item_tag`、`item_preproc`、トリガー（関数を新しいアイテムに付け替え、式・復旧式の `{functionid}` を書き換え）、`trigger_tag`、グラフと `graphs_items`（Y 軸のアイテムも付け替え）、テンプレート内のトリガー依存を複製する。ID の払い出し順は API と同じ（6.0 はテンプレートの ID 順、7.0 以降は `items` の索引 (hostid, key_) の順に読んだ順）。8.0 は `host_template_cache`（ホスト・テンプレート）、`item_template_cache`（アイテム・テンプレート / ホスト）、`trigger_rtdata` も書く。リンク解除は複製の `templateid` を空にして通常のホストの要素として残し、リンク解除してクリアは削除（T-408 と同じ処理）。
- アイテム（T-406）：`items`（6.0 は `name_upper`。TEXT 列 `params` / `query_fields` / `posts` / `headers` は空文字）、空の `item_rtdata`、`item_tag`、7.0 は `item_rtname`。
- トリガーアクション（T-407）：作成は `actions`（`formula` は空、7.0 は `pause_symptoms` も）、`conditions`、`operations`（復旧時・更新時はステップ 1-1・間隔 0）、`opmessage`（全メディアは `mediatypeid` NULL、デフォルトメッセージは件名・本文が空）、`opmessage_grp`、`opmessage_usr`。更新は定義全体を受け取り、既存行への対応付けは API（CAction::update）と同じ：条件は種類と値（タグ値はタグ名）、実行内容は区分ごとに種類、送信先はユーザーグループ / ユーザー。対応した行は変わった列だけ更新し、残りは削除、足りない分は追加する。デフォルトメッセージに切り替えても保存済みの件名・本文は残る（API と同じ）。削除する実行内容は、プレビューと監査ログに出るよう `opmessage*` を明示的に消してから消す。権限はサーバー管理者のみ（D-09）。
- 削除（T-408）：アイテムは API（6.0 `CItemManager::delete`、7.0 `CItem::deleteForce`）と同じく、アイテムがなくなるグラフ（と `profiles` の最新グラフ）、他のグラフの Y 軸参照（計算に戻す）、`profiles` のお気に入り、アイテムを使うトリガー（`functions`・`trigger_tag`・`trigger_depends`）とそのイベントの `housekeeper` 行、アイテムごとの `housekeeper` 行（events、ヒストリ 5 / 6 表、トレンド 2 表。`config.hk_history_mode` / `hk_trends_mode` に従う）、`item_preproc`・`item_tag`・`items` を消す（`item_rtdata` などは ON DELETE CASCADE）。ホストは全アイテムを同様に消し、`host_tag`・`hosts_groups`・`interface`・`hosts_templates`・`host_inventory`・7.0 は `host_hgset` を消してから `hosts` を消す。使われなくなった `hgset` は残す（API と同じ）。アクションは受信者・メッセージ・実行内容・条件・本体の順に消す。6.0 と 7.0 で API の動きが違う場合（6.0 はトリガー / ホストを使うアクションを無効にして条件を消す、7.0 は拒否）と、マップ・メンテナンスなど他の設定が変わる場合は拒否する。`housekeeper` は Zabbix サーバーが処理して消すため、正解比較では行数と種類を別に比べる。

### 4.6 テスト方針：正解との比較
- 検証用 Zabbix コンテナで、**同じ操作を Zabbix API と tamacat-monitor の Writer の両方で実行し、DB の行を比較する**。ID・時刻などの不定値は正規化してから比較する。
- この比較を「6.0 / 7.0 / 8.0 × MySQL / PostgreSQL」の組み合わせで回帰テストとして持つ。
- テスト用の依存はリリース物に含めない。
- 実装（`GoldenMaster`）：全テーブルのチェックサムを前後で取り、変わったテーブルを行単位で比較する。Zabbix サーバーが常に更新するテーブル・列（履歴、イベント、利用可否など）と Zabbix の監査ログは除く。新しい ID は出現順の記号（列名と値の組ごと。別テーブルの同じ番号や status=1 のような値を取り違えない）、uuid は固定値に正規化する。`ids` は構造比較から外し、整合性（nextid ≥ 新しい ID）を別に確認する。

---

## 5. 統合管理 DB（MySQL）

| テーブル | 内容 |
|---|---|
| `users` | ログイン ID、表示名、パスワードハッシュ、ロケール、有効フラグ、ロック状態 |
| `roles` / `user_roles` | `SYSTEM_ADMIN` / `SERVER_ADMIN` / `OPERATOR` / `VIEWER` |
| `user_scopes` | ユーザー × Zabbix サーバー × ホストグループ（groupid と名前パスを保持、子グループを含むかのフラグ） |
| `zabbix_servers` | 名前、検出バージョン、DB URL、参照用 / 更新用 DB ユーザー、暗号化パスワード、有効フラグ、参照専用フラグ |
| `audit_log` | 操作者、日時、対象サーバー、対象種別 / ID、操作、変更前後の差分（JSON） |
| `login_attempts` | ロックアウト判定用 |
| `dashboard_cache` | サーバー単位の集計スナップショット |
| `schema_version` | 自作の簡易マイグレーション（`db/migration/V001__*.sql`） |

---

## 6. 認証・認可・セキュリティ

### 6.1 認証
- ログイン画面（Cloudscape）から `POST /api/login` + サーバー側セッション。
- パスワード：PBKDF2-HMAC-SHA256、既定 600,000 回（JDK 標準、設定可能）。反復回数を上げたら次回ログイン時に再ハッシュする。
- 存在しないユーザーやロック中でも同じ時間をかけて検証し、エラー文言も 1 種類にする（ユーザーの存在を推測させない）。
- 2 段階認証（T-602、任意設定）：TOTP（RFC 6238、HMAC-SHA1・6 桁・30 秒・前後 1 ステップ）。秘密鍵は `users.mfa_secret` に AES-256-GCM（付加データ `mfa:<user_id>`）で保存し、最後に受け付けたステップ（`mfa_last_step`）より後のコードだけを受け付ける（同じコードの再利用不可）。リカバリーコードは 10 個（約 50 ビット）で SHA-256 だけを `user_recovery_codes` に保存し、1 回限り。ログインはパスワードが正しいとセッションに「コード待ち」だけを記録し（未認証のまま）、`POST /api/login/mfa` でコードが正しければセッション ID を変えて認証済みにする。コード待ちは 5 分・5 回まで、誤りはアカウントの連続失敗に数える（ロック）。設定はアカウント画面（QR コードは画面内で生成し、外部に送らない）、解除はパスワードとコード、端末紛失時はシステム管理者がリセットする。
- ログイン成功時に `changeSessionId()` と CSRF トークンの再発行。Cookie は HttpOnly / Secure / SameSite=Strict、名前は `TMONSESSION`。
- ロックアウト：連続 5 回失敗で 15 分（設定可能）。すべての試行を `login_attempts` と `audit_log` に記録する。
- 初期管理者：ユーザーが 0 人のとき `TMON_ADMIN_INITIAL_PASSWORD(_FILE)` から作成し、初回サインインでパスワード変更を強制する。管理者が再設定したパスワードも同様。
- セッションにはユーザー ID だけを持ち、**リクエストごとに DB からユーザーを読み直す**。ロール変更・無効化・ロックが即座に反映される。
- パスキー（T-801、WebAuthn）：`TMON_WEBAUTHN_RP_ID` を設定すると有効。2 通りに使う。
  - **パスキーだけでサインイン**：ログイン ID を入力しない discoverable credential。ユーザー検証（生体認証・PIN）を必須にするので、パスキー 1 つで多要素の扱い。失敗は接続元ごとの失敗に数える。
  - **パスワードの後の 2 段階目**：TOTP の代わりに、そのユーザーのパスキー（ユーザー検証は不要）。失敗はアカウントの連続失敗に数える。パスキーか TOTP があるユーザーは、パスワードの後に必ず 2 段階目を求める。
  - 検証は依存ライブラリなしの自前実装（`security.webauthn`：CBOR・COSE 鍵・認証器データ・clientDataJSON の最小パーサー）。対応アルゴリズムは ES256・EdDSA（Ed25519）・RS256（2048 ビット以上）。attestation は検証しない（none 方針）。オリジンは設定値と完全一致、cross-origin は拒否、RP ID ハッシュ・ユーザー存在（UP）を確認し、署名カウンターが増えない応答は複製の疑いとして拒否（同期パスキーは常に 0 で対象外）。チャレンジは 32 バイトの乱数をセッションに保存し、5 分以内に 1 回だけ使う。
  - 保存（`user_passkeys`）：認証器のクレデンシャル ID（全ユーザーで一意）、COSE 公開鍵、署名カウンター、名前、同期の有無。ユーザーハンドルは数値の ID ではなく `users.webauthn_user_id` の乱数。登録と削除には現在のパスワードが必要。端末紛失時はシステム管理者の「2 段階認証とパスキーのリセット」で TOTP と全パスキーを消す。

### 6.2 ロールと権限
| ロール | 範囲 |
|---|---|
| `SYSTEM_ADMIN` | 全サーバー、ユーザー管理、サーバー登録 |
| `SERVER_ADMIN` | 割り当てられたサーバーの全設定、アクションの編集 |
| `OPERATOR` | 許可ホストグループ内のホスト / アイテムの編集、アクションログの参照 |
| `VIEWER` | 許可ホストグループ内の参照のみ |

- Zabbix を参照・更新するすべてのメソッドで `AccessScope` を必須引数にし、**リポジトリ層で `groupid IN (...)` を強制**する。
  - 実装（P2）：`AccessScope` は All（システム管理者・担当サーバーのサーバー管理者）/ Groups（許可ホストグループ）/ None。ホストは `exists (select 1 from hosts_groups ... groupid in (...))`、障害・アラートはトリガーの関数 → アイテム → ホストのグループで判定する。
  - サブグループ：スコープの「サブグループを含む」が有効なら、Zabbix 上の現在のグループ名に `名前/` で前方一致するグループも許可する。
  - スコープ外のサーバーやホストは 404、サーバー管理者専用の参照（アクション）は 403 にする。
- アクションは Zabbix 上でグローバルなため、編集は `SERVER_ADMIN` 以上に限定する。一般ユーザーは、許可範囲のホストに関するアクションログだけを参照できる。
- ホスト作成時は、許可グループ以外を指定できないようにする。

### 6.3 その他
- 秘密情報（Zabbix DB パスワード）は AES-256-GCM で暗号化する。鍵は `TMON_SECRET_KEY(_FILE)`（base64 の 32 バイト）。暗号化時の付加データに「列名:サーバー ID」を入れ、別の行や列に値をコピーしても復号できないようにする。パスワードは API から返さない（書き込み専用）。
- ログインの失敗制限：アカウントごと（既定 5 回で 15 分ロック）に加え、接続元ごと（既定 15 分に 20 回失敗で拒否）。接続元は `X-Forwarded-For` をループバックからの要求でだけ信頼し、末尾の値を使う（T-503、docs/SECURITY.md）。
- CSRF：状態を変えるリクエスト（ログインを含む）は `X-CSRF-Token` ヘッダが必須。
- レスポンスヘッダ：CSP（`script-src 'self'; style-src 'self'`、インライン script なし）、X-Frame-Options DENY、X-Content-Type-Options、Referrer-Policy no-referrer、Permissions-Policy、HTTPS 時は HSTS。動的レスポンスは `Cache-Control: no-store`。
- 画面の初期データは `<script type="application/json">` に入れ、`<`、`>`、`&` をエスケープする。
- Zabbix DB の JDBC URL は許可リストで検証する（MySQL / PostgreSQL、ホスト・ポート・DB 名のみ、TLS とタイムアウトのパラメータのみ）。URL で認証情報や危険なドライバオプション（LOCAL INFILE、自動デシリアライズなど）を指定できない。MySQL ドライバにはそれらを無効にするプロパティも常に渡す。
- API の例外は種類ごとにステータスコードとメッセージキーへ変換し、内部情報（スタックトレース・SQL）は返さない。
- ログには秘密情報を出さない（tamacat-logging の `mask.keys`。SQL ログは文のみで値は出さない）。

---

## 7. UI 設計

- **JSP** が画面シェル（ヘッダ、ナビゲーション、認証状態、CSRF トークン、ロケール、初期データ）を描画する。
- 各画面は **Cloudscape React のアイランド**（`tamacat-monitor-ui` で esbuild によりページ単位にバンドル）として JSP に埋め込む。
- データは Servlet の JSON API（`/api/...`）から取得する。
- i18n：文言はサーバー側の `ResourceBundle`（`messages.properties`（英語）/ `messages_ja.properties`）に一本化し、ページの初期データとして画面に渡す。API のエラーもメッセージキーで返し、画面側で翻訳する。Cloudscape 部品自体の文言は `I18nProvider`（`ja` / `en`）。ロケールはユーザー設定 → `Accept-Language` の順で決める。英語と日本語のキーが一致していることをテストで確認する。
- 画面：サインイン、ホーム（ダッシュボードは P3）、アカウント（言語、パスワード変更）、Zabbix サーバー（登録・接続テスト・状態確認）、ユーザー（作成・編集・パスワード再設定・ロック解除・担当サーバーと許可ホストグループの割り当て）。
- 表示設定（ユーザーメニュー）：テーマ（コンパクト 13px・高密度 12px・標準 14px。既定はコンパクト）とモード（OS の設定 / ライト / ダーク）。ブラウザーの localStorage に保存する。テーマのトークンは CSP（`style-src 'self'`）がインラインの `<style>` を禁じるため、`generateThemeStylesheet` の結果を CSSOM（`adoptedStyleSheets`）で適用する。
- 一覧（`DataTable`）：Cloudscape の「保存済みフィルター付きテーブル」に倣い、プロパティフィルター（値の候補、AND / OR）、保存済みフィルターセット、列の並べ替え、列幅の変更、ヘッダー固定、ページ分割、一覧の設定（行数・列の表示と順序・折り返し・縞模様・先頭列の固定）を持つ。サーバーから取得した行をブラウザーで絞り込む。取得条件（サーバー、期間、キーワード）はフィルターの上に置き、取得件数が上限に達したら知らせる。設定・列幅・フィルターセットは一覧ごとに localStorage に保存する。
- 詳細画面：ホスト・アイテム・アクション・ホストグループ・ユーザー・Zabbix サーバーは、一覧の名前のリンクから詳細画面（`/hosts?server=&id=`、`/users?id=` など、同じページバンドル）へ移り、パンくずリストで一覧に戻る。作成後（ユーザー・サーバー）は新しい詳細画面へ、削除後は一覧へ移り、結果を表示する。
- 操作ボタン：1 件への操作は詳細画面で行い、一覧では行を選択させない（一覧のヘッダーは作成ボタンだけ）。詳細画面のヘッダーは主ボタン（編集）と、その他の操作をまとめた「操作」ドロップダウン。複数行への一括操作を加えるときだけ、一覧にチェックボックスの選択を付ける。

---

## 8. 運用

- Docker イメージ：tamacat-httpd 1.7 イメージに `monitor.war` と設定を追加する。
- tamacat-httpd のハンドラは既定で GET / HEAD / POST / OPTIONS しか通さない。JSON API の PUT / DELETE のため、`TomcatHandler` の `allowMethods` を設定する（`docker/monitor/conf/tomcat/components.xml`）。
- npm 11 は依存パッケージのインストールスクリプトを既定で実行しない（esbuild の postinstall も実行されないが、ビルドには影響しない）。この挙動は維持する。
- ログ：標準出力（コンテナでは ECS JSON を推奨）。
- 設定：環境変数を優先し、次に設定ファイル。
- ヘルスチェック：`/health`（統合 DB と各 Zabbix DB の接続状態）。

---

## 9. 確認事項（外部要因）

- Zabbix の商標ガイドライン（製品名・画面表記での「Zabbix」の扱い）。
