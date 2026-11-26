# tamacat-monitor

**tamacat-monitor for Zabbix** — 複数の Zabbix サーバー（6.0 / 7.0 / 8.0）を 1 つの Web コンソールから管理する Java Web アプリケーション。

> **非公式のサードパーティー製品です。** tamacat-monitor は Zabbix LLC とは関係がなく、Zabbix LLC の承認・支援を受けたものではありません。
> Zabbix is a registered trademark of Zabbix LLC.

- Java 25 / Jakarta Servlet 6.1 / JSP + JSTL
- tamacat-httpd 1.7 + Tomcat Embedded 11
- Cloudscape Design System（UI）
- 統合管理 DB: MySQL、Zabbix DB: MySQL（PostgreSQL 対応予定）
- Spring・PHP 不使用、依存ライブラリ最小

## モジュール

| モジュール | 内容 |
|---|---|
| `tamacat-monitor-dao` | tamacat-dao 2.0 改良版（bind 専用 SQL、MySQL / PostgreSQL 方言、トランザクション、独自コネクションプール） |
| `tamacat-monitor-core` | 設定、統合管理 DB、スキーマ移行、ユーザー・認証、監査ログ、暗号化 |
| `tamacat-monitor-zabbix` | Zabbix DB アクセス（バージョン差の吸収）、Zabbix サーバー登録 |
| `tamacat-monitor-ui` | Cloudscape（React）の画面バンドル（npm / esbuild） |
| `tamacat-monitor-admin` | Servlet / JSP / JSON API（`monitor.war`） |

## ビルド

JDK 25、Maven、Node.js 24（npm 11）が必要。

```bash
mvn -B verify
```

npm を使わずに Java 側だけビルドする場合は `-Dskip.ui`（`tamacat-monitor-ui/dist` の既存の成果物を使う）。

`tamacat-monitor-admin/target/monitor.war` を tamacat-httpd 1.7 の `webapps/` に置く。Docker での起動は [docker/README.md](docker/README.md)。

## ドキュメント
- [設計書](docs/DESIGN.md)
- [課題・タスク一覧](docs/ISSUES.md)
- [将来構想・ロードマップ](docs/ROADMAP.md)
- [tamacat-monitor-dao（tamacat-dao 2.0 からの変更点）](tamacat-monitor-dao/README.md)
- [Docker 開発環境](docker/README.md)

## ライセンス

[Apache License 2.0](LICENSE)。著作権と商標の表記は [NOTICE](NOTICE)。

tamacat-monitor は Zabbix のソースコードを含まない。Zabbix の DB と API に接続して動く。

## ステータス
P9：名称を tamacat-monitor に変更し、公開用リポジトリに移行（非公式・商標の表記、Apache-2.0）。開発・テスト用の Zabbix はカスタムビルド版（[zabbix-6.0-custom](https://github.com/tamacat/zabbix-6.0-custom)、[7.0](https://github.com/tamacat/zabbix-7.0-custom)、[8.0](https://github.com/tamacat/zabbix-8.0-custom)）の各系列の最新版。

P8：パスキー（WebAuthn）。パスキーだけでのサインインと、パスワード後の 2 段階目。

P7：画面デザインの調整（テーマ・文字サイズ、フィルター・並べ替え・列幅変更・ヘッダー固定のある一覧、詳細画面とパンくずリスト、操作のドロップダウン）。

P6：テンプレートのリンク / リンク解除（範囲限定）、TOTP による 2 段階認証。

P5：Zabbix 8.0 対応（参照・書き込み）、脆弱性診断（[docs/SECURITY.md](docs/SECURITY.md)）、負荷試験（[docs/LOADTEST.md](docs/LOADTEST.md)）。PostgreSQL 版 Zabbix DB は保留。

P4（Zabbix DB への直接書き込み）完了：ホストグループ（作成・名前変更・削除）、ホスト（作成・更新）、アイテム（作成・更新）、トリガーアクション（作成・更新）と、ホスト・アイテム・アクションの削除。
