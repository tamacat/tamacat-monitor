# 将来構想・ロードマップ（tamacat-monitor）

tamacat-monitor の次の段階として、名称変更、既存ツールの活用、マルチテナント化をどう進めるかをまとめる。

- ステータス: 構想（2026-11-23）。決定事項は [ISSUES.md](ISSUES.md) の「決定事項」に移してから実装する。
- 現行の設計は [DESIGN.md](DESIGN.md)、セキュリティは [SECURITY.md](SECURITY.md) を参照。

---

## 1. 名称と公開方針

### 1.1 名称（P9 で決定）
| 項目 | 決定 |
|---|---|
| 製品名 | **tamacat-monitor**（説明では「tamacat-monitor for Zabbix」と書く） |
| リポジトリ | https://github.com/tamacat/tamacat-monitor |
| ライセンス | Apache License 2.0（[LICENSE](../LICENSE)、[NOTICE](../NOTICE)） |
| モジュール | `tamacat-monitor-*`（dao / core / zabbix / ui / admin） |
| パッケージ | `org.tamacat.monitor` |
| 識別子の接頭辞 | 環境変数 `TMON_`、設定キー・`localStorage`・セッション属性 `tmon.`、cookie `TMONSESSION`、統合管理 DB `tmon`、Zabbix DB のアカウント `tmon_read` / `tmon_write` |
| 配備 | `monitor.war`（URL は `/monitor/`） |

名前の方針:
- 公式製品と誤解されない名前にする（製品名に Zabbix を含めない）。
- Zabbix の商標ガイドラインは、Zabbix を短くした名前や略語（例「ZabCon」）を認めていない。`zbx` のような略称も使わない。
- `TAMACAT_` は tamacat-logging などの環境変数と衝突するので使わない。

### 1.2 商標・非公式の表記（Q-06）
[Zabbix Trademark Policy](https://www.zabbix.com/trademark) による（2026-11 時点で確認）。
- 製品名・ドメイン・ロゴに「Zabbix」「ABBIX」を含めない。オープンソースの「… for Zabbix」は許可された使い方として例示されている。
- README・画面のフッター・ドキュメントに次の 2 つを書く。
  - 非公式であること:「tamacat-monitor は Zabbix LLC とは関係のない非公式のサードパーティー製品です。」
  - 商標の表記:「Zabbix is a registered trademark of Zabbix LLC.」
- Zabbix のロゴは使わない。「Zabbix server / agent / solution / services」のように Zabbix と組み合わせた名前を製品名に使わない。
- ソースコード中の識別子（`ZabbixQueryService` など）は、接続先を示すための記述的な使い方として扱う。
- 有償で提供する場合は、改めてライセンスの要否を確認する。

### 1.3 公開時の確認
- 開発用の値（`docker/secrets/`、`.env`）がリポジトリに入っていないこと。
- 依存ライブラリのライセンスが Apache-2.0 と矛盾しないこと（tamacat-dao・tamacat-httpd は Apache-2.0、Cloudscape は Apache-2.0、MySQL Connector/J は GPL-2.0 + FOSS 例外。Connector/J は利用者の環境で読み込むもので、配布物に含める場合は例外の条件に従う）。

---

## 2. 目指す姿

Zabbix を中心に、構成管理・統合監視・セキュリティ監視・インシデント管理をつなぐ運用基盤にする。

| 領域 | 実現すること |
|---|---|
| 統合監視 | 複数の Zabbix（6.0 / 7.0 / 8.0）を 1 画面で管理する（現行）。AWS CloudWatch や Azure Monitor のアラートも同じ障害一覧に取り込む |
| 構成管理（CMDB） | Zabbix のホスト、クラウドの資源、アプリケーション（SBOM）を台帳にまとめる。監視の漏れと設定の差分を見つける |
| 脆弱性・EOL | SBOM と OS パッケージから脆弱性、悪用状況（KEV・EPSS）、サポート終了（EOL）を調べ、台帳上のホストやシステムに結びつける |
| インシデント管理 | 障害や脆弱性から tamacat-tracker にチケットを起票し、相互にリンクする |
| セキュリティ監視 | AWS Security Hub、Microsoft Defender for Cloud、Wazuh などの検出結果を取り込む（後期） |
| マルチテナント | 利用者が自分のテナントの範囲だけを参照・申請できるポータルを用意する |

---

## 3. 既存ツールの活用

どちらも tamacat-monitor に取り込まず、独立したまま連携させる。各ツールは単独でも使え、技術スタックの違い（Rust / Spring Boot）が tamacat-monitor の依存方針（Spring を使わない、依存ライブラリを最小にする）に影響しない。

### 3.1 software-update-checker（OSS の最新版・脆弱性チェック）
[dependency-auto-update-tool](https://github.com/tamacat/dependency-auto-update-tool) の `software-update-checker`

| 項目 | 内容 |
|---|---|
| 連携の方法 | 管理コンソールのサーバーから `software-update-checker-cli <入力> --format json` を別プロセスで実行し、結果の JSON を取り込む。ソースコードは共有しない |
| 入力 | CycloneDX / SPDX の SBOM、pom.xml、npm / pnpm / yarn、pip / poetry / uv、Cargo.lock、go.mod、gradle.lockfile |
| 照会先 | OSV、deps.dev、CISA KEV、EPSS、endoflife.date、OpenSSF Scorecard（CLI 側で照会する。tamacat-monitor に OSV の照会コードを重複して持たない） |
| SBOM の集め方 | アプリケーションは、CI で作った CycloneDX を API でアップロードしてもらう。台帳の「システム」に結びつけて保存し、版ごとに履歴を残す |
| OS パッケージ | Zabbix エージェントの `system.sw.packages`（または `system.sw.packages.get`）で集める。checker に OS パッケージ一覧の入力形式（`Importer`）を追加する必要がある。OSV は Debian・Ubuntu・Alpine・Rocky / AlmaLinux などに対応している |
| 定期実行 | 毎日、保存済みの SBOM を再評価する（新しい脆弱性の公開に追従するため） |
| 画面 | システム・ホストごとの脆弱性一覧（重大度、KEV、EPSS、修正版、EOL）。脆弱性からの逆引き（どのテナント・ホストが影響を受けるか） |
| 起票 | 「KEV に載っている」「EPSS が高い」などの条件で tamacat-tracker に起票する（3.2） |

dependency-auto-update-tool（Java CLI：Maven の候補版を `mvn verify` で検証して自動更新する）は開発者向けのツールなので、tamacat-monitor の機能には含めない。tamacat-monitor 自体の依存ライブラリの更新に使う。

### 3.2 tamacat-tracker（チケット・インシデント管理）
別リポジトリ（非公開）

| 項目 | 内容 |
|---|---|
| 連携の方法 | 独立したサービスとして運用し、REST で連携する。tamacat-monitor をエージェントアカウント（OAuth2 クライアント資格情報、`POST /api/v1/auth/agent/token`）として登録し、短命のトークンで呼び出す |
| 資格情報 | クライアントシークレットは管理コンソールの側だけに、既存の暗号化設定と同じ方法で保存する |
| 起票 | 障害の詳細画面の「インシデント登録」ボタン。規則（重大度が High 以上、特定のホストグループなど）による自動起票。脆弱性の検出結果からの起票 |
| 相互リンク | 障害（Zabbix サーバー ID + イベント ID）とチケット番号の対応表を tamacat-monitor に持つ。チケットには障害詳細画面への URL を書く |
| 状態の連携 | 障害が回復したらチケットにコメントを追記する（`POST /api/v1/tickets/{id}/comments`）。チケットの状態は定期的に取りに行き、画面に表示する |
| Zabbix への反映 | チケット完了時に Zabbix の障害を確認済み（acknowledge）にするかは任意。DB への直接書き込みでは扱いにくいので、これだけは Zabbix API を使う想定 |
| テナント | テナント 1 つに tracker のプロジェクト 1 つを割り当てる。プロジェクトごとの権限（TICKET_VIEW など）がそのままテナントの区切りになる |

tracker 側に必要になりそうな拡張:
- 外部キー（障害の識別子）でのチケット検索と、同じ障害からの二重起票の防止。
- 状態変化の Webhook（定期的な取得をやめたくなった場合）。

利用者のアカウントは tamacat-monitor と tracker で別々になる。当面は利用者ポータルが tracker を代理で呼び出し、利用者が tracker に直接ログインしなくて済むようにする。将来は OIDC などによるシングルサインオンを検討する。

---

## 4. マルチテナント構成

### 4.1 管理コンソールと利用者ポータルを分ける

```
［インターネット］                              ［社内ネットワーク（非公開）］

 利用者 ─HTTPS─ 利用者ポータル ◀── 集約データを送る ── 管理コンソール ── Zabbix DB（直接読み書き）
               (monitor-portal)   （社内から外への                (monitor-admin) ── AWS / Azure API
               ・自テナントだけ    　一方向の通信のみ）           ・システム管理者 ── software-update-checker CLI
               ・参照と申請が中心                                ・設定変更        ── tamacat-tracker（REST）
               ・Zabbix などの                                  ・資格情報の保管
                 資格情報を持たない ── 申請 ──▶（管理コンソールが取りに行く）
```

| 項目 | 管理コンソール（monitor-admin） | 利用者ポータル（monitor-portal） |
|---|---|---|
| 公開範囲 | 社内ネットワークのみ（原則インターネット非公開） | インターネット |
| 利用者 | システム管理者、サーバー管理者、オペレーター | テナント管理者、テナント運用者、テナント閲覧者 |
| データ | 統合管理 DB、Zabbix DB、クラウド API | ポータル専用 DB（テナント別の集約データ）だけ |
| 資格情報 | Zabbix DB、クラウド、tracker の資格情報を持つ | 持たない |
| 書き込み | Zabbix 設定の変更、起票 | 申請（起票、メンテナンス期間、監視追加の依頼）を記録するだけ |
| 認証 | 現行（パスワード + TOTP / パスキー） | 2 段階認証またはパスキーを必須にする |
| 現行との対応 | 現在の tamacat-monitor-admin | 新規（P15） |

方針:
- **ポータルが乗っ取られても監視基盤を変更されない**ようにする。ポータルには Zabbix DB やクラウドへの接続情報を置かない。
- **通信は社内からポータル側への一方向にする**。管理コンソールがテナント別の集約データ（障害、台帳、脆弱性、チケットの状態、レポート）をポータル専用 DB に送る。インターネット側から社内への接続は受け付けない。
- ポータルでの操作は「申請」としてポータル専用 DB に記録し、管理コンソールが定期的に取りに行って処理する（必要なら承認を挟む）。
- アカウントは別管理にする。管理者と利用者のアカウントを同じ DB に置かない。

### 4.2 データモデル（P10 で先に入れる）
| テーブル | 内容 |
|---|---|
| `tenants` | テナント（ID、名前、状態、連絡先） |
| `tenant_scopes` | テナントに割り当てる範囲。種類と値を持つ |
| `tenant_members`（ポータル側） | 利用者とテナントロール |

`tenant_scopes` の種類:
| 種類 | 値 |
|---|---|
| `ZABBIX_HOSTGROUP` | Zabbix サーバー ID × ホストグループ ID（サブグループを含むかのフラグ。現行の `UserScope` と同じ考え方） |
| `AWS_ACCOUNT` | アカウント ID、リージョン、タグ条件 |
| `AZURE_SUBSCRIPTION` | サブスクリプション ID、リソースグループ |
| `TRACKER_PROJECT` | tamacat-tracker のプロジェクト ID |
| `SYSTEM` | 台帳上のシステム（SBOM の対象） |

規則:
- テナントに属するデータ（台帳、障害の取り込み、チケットとの対応、脆弱性の検出結果、申請）にはすべて `tenant_id` を持たせる。
- 絞り込みはリポジトリ層で必ず行う（画面や API 層の条件に頼らない）。
- 他テナントのデータが見えないことを自動テスト（テナント分離テスト）で確かめる。
- 現行の権限（`UserScope`、`tamacat-monitor-zabbix` の `AccessScope` / `AccessResolver`）は「`ZABBIX_HOSTGROUP` の範囲」の一種として一般化する。管理コンソールのユーザーは従来どおりスコープで絞り、ポータルの利用者はテナントの範囲で絞る。

### 4.3 モジュール構成（P9 で分割）
| モジュール | 内容 | 現行 |
|---|---|---|
| `monitor-dao` | DB アクセス | `tamacat-monitor-dao` |
| `monitor-core` | 設定、ユーザー・認証、監査、暗号化、テナント、台帳 | `tamacat-monitor-core` |
| `monitor-zabbix` | Zabbix 接続（参照・書き込み） | `tamacat-monitor-zabbix` |
| `monitor-aws` / `monitor-azure` | クラウド接続（P13 / P14） | 新規 |
| `monitor-ui` | 画面バンドル | `tamacat-monitor-ui` |
| `monitor-admin` | 管理コンソールの Web アプリ | `tamacat-monitor-admin` |
| `monitor-portal` | 利用者ポータルの Web アプリ（P15） | 新規 |

ポータルは `monitor-zabbix` やクラウド接続のモジュールに依存しない（ビルドの依存関係で資格情報を扱うコードが入らないようにする）。

---

## 5. ロードマップ

| フェーズ | 内容 | 完了の目安 |
|---|---|---|
| P9 | 名称変更と公開用リポジトリの作成。非公式・商標の表記。モジュール名の変更 | 新リポジトリでテストがすべて通る。README と画面に表記がある |
| P10 | テナントのデータモデルと範囲の割り当て（管理コンソールで管理）。接続先の抽象化（Zabbix 以外を足せる形） | テナント分離テストがある |
| P11 | tamacat-tracker との連携（障害からの起票、相互リンク、回復時のコメント） | 障害詳細画面から起票でき、チケットから戻れる |
| P12 | 構成管理：台帳、SBOM の取り込み、software-update-checker との連携、Zabbix 設定の差分検出 | システム別の脆弱性一覧と、脆弱性からの起票ができる |
| P13 | AWS：資源の検出、Zabbix への登録の提案、CloudWatch アラームの取り込み | 監視されていない EC2 / RDS を一覧できる |
| P14 | Azure：同様（Azure Monitor のアラート） | 同上 |
| P15 | 利用者ポータル：ポータル専用 DB への一方向の送信、テナント別の表示、申請 | 社内の管理コンソールに外部からの接続なしで運用できる |
| P16 以降 | セキュリティの検出結果の統合（Security Hub、Defender、Wazuh）、通知、承認ワークフロー、AI による一次切り分け（tracker のエージェント API を利用） | — |

ポータルを P15 に置くのは、それまでのフェーズで、テナントに見せるデータ（障害、台帳、脆弱性、チケット）がそろうため。ただしデータモデル（P10）とモジュール分割（P9）は最初からポータルを前提にしておき、後から作り直すのは画面の部分だけで済むようにする。

---

## 6. 未決事項

| ID | 内容 |
|---|---|
| R-01 | 最終的な名称（tamacat-monitor で確定するか） |
| R-02 | 公開時のライセンス |
| R-03 | ポータルへのデータの送り方：管理コンソールがポータル専用 DB に直接書くか、ポータルの受け口 API に送るか |
| R-04 | 管理コンソールとポータルの間、tracker との間のシングルサインオン（OIDC）を導入する時期 |
| R-05 | OS パッケージの脆弱性照会を software-update-checker に追加するか、別の方法にするか |
| R-06 | Zabbix の確認済み（acknowledge）など、DB への直接書き込みに向かない操作に Zabbix API を併用するか |
| R-07 | パスキーの実機確認（Windows Hello、スマートフォン）。P8 はソフトウェアの認証器でのみ確認済み |
