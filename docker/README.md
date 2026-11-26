# コンテナ開発環境

## 構成

| サービス | 内容 |
|---|---|
| `monitor-db` | 統合管理 DB（MySQL 8.4） |
| `monitor` | tamacat-httpd 1.7 + `monitor.war`。http://127.0.0.1:8080/monitor/ |
| `zabbix60-*` / `zabbix70-*` | 検証用 Zabbix 6.0 / 7.0（MySQL）。`--profile zabbix` のときだけ起動 |

`tamacat-monitor` イメージは、tamacat-httpd 1.7 のイメージに WAR と設定（`docker/monitor/conf`）を追加したもの。

- httpd はコンテナ内で **8080** を待ち受け、内部の Tomcat は 127.0.0.1:8081。
- JSON API が PUT / DELETE を使うため、`conf/tomcat/components.xml` で TomcatHandler の `allowMethods` を広げている（tamacat-httpd の既定は GET,HEAD,POST,OPTIONS で、それ以外は httpd が 405 を返す）。
- **公開ポートも 8080 にそろえる**こと（`-p 8080:8080`）。tamacat-httpd 1.7 は、外から見たポートと待ち受けポートが違うと Host ヘッダを壊し、Tomcat が 400 を返す（docs/ISSUES.md Q-07）。

## 1. ベースイメージ

既定では公開イメージ `docker.io/tamacat/tamacat-httpd:1.7` を使う。
最新ビルドで試す場合は、tamacat-httpd リポジトリでイメージを作り、`HTTPD_IMAGE` で指定する。

```bash
git clone -b v1.7 https://github.com/tamacat/tamacat-httpd.git
cd tamacat-httpd
mvn clean package
mkdir -p docker/tamacat/target
cp target/tamacat-httpd-1.7.0-b20261120-jar-with-dependencies.jar docker/tamacat/target/
podman build --build-arg APP_VERSION=1.7.0-b20261120 -t localhost/tamacat-httpd:1.7.0-b20261120 docker/tamacat
```

## 2. シークレット

`docker/secrets/` に次のファイルを作る（Git 管理外）。

```bash
openssl rand -base64 24 | tr -d '\r\n' > docker/secrets/tmon_db_password.txt
openssl rand -base64 24 | tr -d '\r\n' > docker/secrets/mysql_root_password.txt
# Zabbix DB パスワードを暗号化する AES-256 鍵。失うと登録済みサーバーのパスワードを復号できない
openssl rand -base64 32 | tr -d '\r\n' > docker/secrets/tmon_secret_key.txt
# 初期管理者 admin のパスワード（ユーザーが 0 人のときだけ使われ、初回サインインで変更を求められる）
openssl rand -base64 18 | tr -d '\r\n' > docker/secrets/tmon_admin_password.txt
```

`tr -d '\r\n'` で改行を除く。Windows の openssl は Git Bash からでも行末に CRLF を書く。MySQL イメージは行末の LF だけを除いて CR を残すため、CR がパスワードの一部として登録され、行末の空白を除いて読む tamacat-monitor から接続できなくなる（`Access denied`）。

## 3. Docker Compose で起動

```bash
mvn -B package
docker compose -f docker/compose.yml up -d --build
docker compose -f docker/compose.yml --profile zabbix up -d
```

- 起動時に統合管理 DB のスキーマが自動作成される（`tamacat-monitor-core/src/main/resources/db/migration`）。
- 確認：http://127.0.0.1:8080/monitor/health → `{"status":"UP"}`
- 画面：http://127.0.0.1:8080/monitor/ に `admin` と初期パスワードでサインインする。

## 3'. Podman で起動（Windows の podman machine）

`podman compose` が外部プロバイダ（docker-compose.exe）を呼んでも podman に接続できない環境では、同じ構成を個別に起動する。Git Bash では `/run/secrets/...` が Windows のパスに変換されるため、`MSYS_NO_PATHCONV=1` を付ける。

```bash
export MSYS_NO_PATHCONV=1
podman network create monitor-net
podman secret create tmon_db_password docker/secrets/tmon_db_password.txt
podman secret create mysql_root_password docker/secrets/mysql_root_password.txt
podman secret create tmon_secret_key docker/secrets/tmon_secret_key.txt
podman secret create tmon_admin_password docker/secrets/tmon_admin_password.txt

podman run -d --name monitor-db --network monitor-net -v monitor-db:/var/lib/mysql \
  --secret tmon_db_password --secret mysql_root_password \
  -e MYSQL_DATABASE=tmon -e MYSQL_USER=tmon \
  -e MYSQL_PASSWORD_FILE=/run/secrets/tmon_db_password \
  -e MYSQL_ROOT_PASSWORD_FILE=/run/secrets/mysql_root_password \
  docker.io/library/mysql:8.4 --character-set-server=utf8mb4 --collation-server=utf8mb4_bin

podman build -f docker/monitor/Dockerfile -t localhost/tamacat-monitor:dev .
podman run -d --name monitor --network monitor-net -p 127.0.0.1:8080:8080 \
  --secret tmon_db_password --secret tmon_secret_key --secret tmon_admin_password \
  -e TMON_DB_URL=jdbc:mysql://monitor-db:3306/tmon -e TMON_DB_USER=tmon \
  -e TMON_DB_PASSWORD_FILE=/run/secrets/tmon_db_password \
  -e TMON_SECRET_KEY_FILE=/run/secrets/tmon_secret_key \
  -e TMON_ADMIN_INITIAL_PASSWORD_FILE=/run/secrets/tmon_admin_password \
  -e TMON_SESSION_COOKIE_SECURE=false \
  -e TMON_WEBAUTHN_RP_ID=localhost -e TMON_WEBAUTHN_ORIGINS=http://localhost:8080 \
  localhost/tamacat-monitor:dev
```

検証用 Zabbix には、公式イメージではなく**カスタムビルド版の各系列の最新版**（`docker.io/tamacat/zabbix-server-mysql:<X.Y>-latest`、`docker.io/tamacat/zabbix-web-nginx-mysql:<X.Y>-latest`）を使う。ビルド元は [zabbix-6.0-custom](https://github.com/tamacat/zabbix-6.0-custom)・[zabbix-7.0-custom](https://github.com/tamacat/zabbix-7.0-custom)・[zabbix-8.0-custom](https://github.com/tamacat/zabbix-8.0-custom)（上流の新版は各リポジトリの `scripts/check-zabbix-version.sh` で確認できる）。

| 系列 | MySQL | DB の公開ポート | Web（API）の公開ポート | 確認した版 |
|---|---|---|---|---|
| 6.0 | 8.0 | 13360 | 18060 | 6.0.49 |
| 7.0 | 8.4 | 13370 | 18070 | 7.0.32 |
| 8.0 | 8.4 | 13380 | 18080 | 8.0.0 |

カスタムビルド版のサーバーイメージは、公式イメージと違い**空の DB にスキーマを作らない**。DB コンテナの初回起動時に `docker/zabbix-init/00-load-zabbix-schema.sh` が、カスタムビルドのソースツリー（`sources/zabbix-<版>/database/mysql`）から `schema.sql`・`images.sql`・`data.sql` を読み込む。compose では `ZABBIX60_SCHEMA_DIR` / `ZABBIX70_SCHEMA_DIR` / `ZABBIX80_SCHEMA_DIR` でその場所を変えられる（既定は、このリポジトリと同じ階層に clone した `../zabbix-<X.Y>-custom/sources/...`。別の場所なら `docker/.env` に書く）。

6.0 の例（7.0 / 8.0 は名前・MySQL・ポート・ソースの版を上の表に合わせる）：

```bash
export MSYS_NO_PATHCONV=1
podman run -d --name zabbix60-db --network monitor-net -p 127.0.0.1:13360:3306 \
  -e MYSQL_DATABASE=zabbix -e MYSQL_USER=zabbix -e MYSQL_PASSWORD=zabbix -e MYSQL_ROOT_PASSWORD=zabbix-root \
  -v "$PWD/docker/zabbix-init:/docker-entrypoint-initdb.d:ro" \
  -v "$PWD/../zabbix-6.0-custom/sources/zabbix-6.0.49/database/mysql:/zabbix-schema-src:ro" \
  docker.io/library/mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_bin --log-bin-trust-function-creators=1
podman run -d --name zabbix60-server --network monitor-net --cap-add NET_RAW \
  -e DB_SERVER_HOST=zabbix60-db -e MYSQL_DATABASE=zabbix -e MYSQL_USER=zabbix -e MYSQL_PASSWORD=zabbix \
  docker.io/tamacat/zabbix-server-mysql:6.0-latest
podman run -d --name zabbix60-web --network monitor-net -p 127.0.0.1:18060:8080 \
  -e DB_SERVER_HOST=zabbix60-db -e MYSQL_DATABASE=zabbix -e MYSQL_USER=zabbix -e MYSQL_PASSWORD=zabbix \
  -e ZBX_SERVER_HOST=zabbix60-server -e PHP_TZ=UTC docker.io/tamacat/zabbix-web-nginx-mysql:6.0-latest
```

新しい版に上げるときは `podman pull` してから server / web のコンテナだけを作り直す（DB はそのまま。サーバーが起動時に DB を更新する）。tamacat-monitor からは `jdbc:mysql://zabbix60-db:3306/zabbix` / `jdbc:mysql://zabbix70-db:3306/zabbix`（ユーザー `zabbix` / パスワード `zabbix`）で接続できる。**検証専用の固定パスワードなので、本番では使わないこと。**

## 実スキーマでの統合テスト（RealSchemaTest）

検証用 Zabbix の DB をホストに公開して起動し、環境変数で指定すると `tamacat-monitor-zabbix` の `RealSchemaTest` が実行される（未指定ならスキップ）。テストは ID 990000 番台のデータを一時的に投入・削除するため、**使い捨ての検証用 DB にだけ**使うこと。

```bash
podman run -d --name zabbix60-db --network monitor-net -p 127.0.0.1:13360:3306 ...   # 7.0 は 13370
export TMON_IT_ZABBIX60_URL=jdbc:mysql://127.0.0.1:13360/zabbix
export TMON_IT_ZABBIX70_URL=jdbc:mysql://127.0.0.1:13370/zabbix
mvn -pl tamacat-monitor-zabbix -am -Dskip.ui test -Dtest=RealSchemaTest -Dsurefire.failIfNoSpecifiedTests=false
```

## 書き込み（P4）の検証環境

正解比較テスト（`*GoldenMasterTest`）には、検証用 Zabbix の Web フロントエンド（API）が必要。

```bash
# Web（API）コンテナは「検証用 Zabbix」の手順で起動したもの（6.0: 18060、7.0: 18070、8.0: 18080）

export TMON_IT_ZABBIX70_URL=jdbc:mysql://127.0.0.1:13370/zabbix
export TMON_IT_ZABBIX70_API=http://127.0.0.1:18070/api_jsonrpc.php
mvn -pl tamacat-monitor-zabbix -am -Dskip.ui test -Dtest='HostGroupGoldenMasterTest,HostGoldenMasterTest,ItemGoldenMasterTest,ActionGoldenMasterTest,DeleteGoldenMasterTest,GrantsTest' -Dsurefire.failIfNoSpecifiedTests=false
```

API には検証用コンテナの既定アカウント（Admin / zabbix）でログインする。`GrantsTest` は MySQL の root（`TMON_IT_ROOT_PASSWORD`、既定 zabbix-root）で `tmon_read` / `tmon_write` を作り直す。

tamacat-monitor から書き込むには、Zabbix DB に `grants/mysql.sql`（7.0 以降は `grants/mysql-7.0.sql` も。`tamacat-monitor-zabbix/src/main/resources/org/tamacat/monitor/zabbix/grants/`）のアカウントを作り、サーバーの編集画面で更新用アカウントを設定して「更新可」にする。

## Zabbix 8.0 の検証環境（P5）

「検証用 Zabbix」の表のとおり、名前を `zabbix80-*`、MySQL を `8.4`、イメージを `8.0-latest`、公開ポートを DB `13380`・Web `18080` にする。統合テストは `TMON_IT_ZABBIX80_URL=jdbc:mysql://127.0.0.1:13380/zabbix`、`TMON_IT_ZABBIX80_API=http://127.0.0.1:18080/api_jsonrpc.php` を追加すると 8.0 でも実行される。8.0 の書き込みアカウントには `grants/mysql-8.0.sql` も実行する。

## 負荷試験（T-503）

`docker/loadtest/`：検証用 7.0 DB に大量データを入れる SQL、削除用 SQL、負荷をかける Python スクリプト（標準ライブラリのみ）。結果と手順は [docs/LOADTEST.md](../docs/LOADTEST.md)。**使い捨ての検証用 DB にだけ使うこと。**

## 設定（環境変数）

| 変数 | 既定値 | 内容 |
|---|---|---|
| `TMON_DB_URL` | （必須） | 統合管理 DB の JDBC URL。認証情報は含めない |
| `TMON_DB_USER` | | ユーザー |
| `TMON_DB_PASSWORD` / `TMON_DB_PASSWORD_FILE` | | パスワード / Docker secret のファイル |
| `TMON_DB_POOL_MIN_IDLE` | 1 | プールの最小アイドル数 |
| `TMON_DB_POOL_MAX_SIZE` | 10 | プールの最大数 |
| `TMON_DB_POOL_CONNECTION_TIMEOUT` | 30 | 接続取得の待ち時間（秒） |
| `TMON_DB_POOL_LEAK_DETECTION` | 0 | 接続の返し忘れの警告までの秒数（0 で無効） |
| `TMON_DB_QUERY_TIMEOUT` | 30 | SQL のタイムアウト（秒） |
| `TMON_DB_SLOW_QUERY` | 1 | スロークエリとして警告する秒数 |
| `TMON_DB_MIGRATE` | true | 起動時にスキーマを更新するか |
| `TMON_SECRET_KEY` / `TMON_SECRET_KEY_FILE` | （必須） | Zabbix DB パスワードを暗号化する鍵（base64 の 32 バイト） |
| `TMON_ADMIN_INITIAL_PASSWORD` / `_FILE` | | 初期管理者のパスワード。ユーザーが 0 人のときだけ使う |
| `TMON_ADMIN_LOGIN_ID` | admin | 初期管理者のログイン ID |
| `TMON_SECURITY_PBKDF2_ITERATIONS` | 600000 | パスワードハッシュの反復回数 |
| `TMON_SECURITY_MAX_LOGIN_FAILURES` | 5 | ロックまでの連続失敗回数 |
| `TMON_SECURITY_LOCK_SECONDS` | 900 | ロック時間（秒） |
| `TMON_SECURITY_MAX_LOGIN_FAILURES_PER_IP` | 20 | 接続元ごとに、この回数の失敗で拒否する（0 で無効） |
| `TMON_SECURITY_IP_WINDOW_SECONDS` | 900 | 接続元ごとの失敗を数える期間（秒） |
| `TMON_MFA_ISSUER` | tamacat-monitor | 認証アプリに表示する発行者名（2 段階認証） |
| `TMON_WEBAUTHN_RP_ID` | （なし） | パスキーの RP ID。tamacat-monitor の URL のホスト名（例 `monitor.example.com`、開発時は `localhost`）。未設定ならパスキーは無効。IP アドレスは使えない |
| `TMON_WEBAUTHN_ORIGINS` | https://RP ID | 受け付けるオリジン（`https://monitor.example.com` のようにスキーム・ホスト・ポート）。カンマ区切りで複数可 |
| `TMON_WEBAUTHN_RP_NAME` | tamacat-monitor | パスキー作成時に端末に表示する名前 |
| `TMON_ZABBIX_POOL_MAX_SIZE` | 5 | Zabbix DB ごとの接続プールの最大数 |
| `TMON_ZABBIX_QUERY_TIMEOUT` | 30 | Zabbix DB への SQL のタイムアウト（秒） |
| `TMON_ZABBIX_SERVER_TIMEOUT` | 20 | 横断検索でサーバー 1 台を待つ時間（秒） |
| `TMON_DASHBOARD_CACHE_SECONDS` | 30 | ダッシュボードの集計をキャッシュする秒数 |
| `TMON_SESSION_COOKIE_SECURE` | true | セッション Cookie の Secure 属性。平文 HTTP の開発時だけ false |
| `TMON_SESSION_TIMEOUT_MINUTES` | 30 | セッションタイムアウト（分） |
| `TAMACAT_LOG_FORMAT` | text | `json` で ECS JSON 形式 |
