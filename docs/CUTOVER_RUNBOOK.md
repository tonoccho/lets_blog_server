# 認証カットオーバー手順書 (Cutover Runbook)

Epic: [#551](https://github.com/tonoccho/lets_blog_server/issues/551) / Phase 21: 品質・運用
Issue: [#591](https://github.com/tonoccho/lets_blog_server/issues/591) [E5] カットオーバー手順とロールバック方針を確定する
関連ADR: [ADR-0002](adr/0002-keycloak-oidc.md)（Keycloak採用）/ [ADR-0003](adr/0003-cutover-migration.md)（一括切り替え方針）

**この手順書は [B8] 旧認証機構の撤去（[#566](https://github.com/tonoccho/lets_blog_server/issues/566)）に着手する前に、
実際にステージング相当の環境で通しで実行し、ロールバックも試行して確認しておく必要がある
（本Issueの受入基準）。2026-08-27、本環境で実際に通し実行およびロールバック試行(データのみ)を
行い、成功を確認した。ただしユーザー移行(3章)の実機実行とフルロールバック(データ+コード)は
未実施。詳細は [9. 実行記録](#9-実行記録) と [10. 既知の制約・未検証事項](#10-既知の制約未検証事項) を参照。**

---

## 0. 前提: 2026-08時点のアーキテクチャ状況

本手順書を正しく使うために、実行前に以下の前提を理解しておく必要がある。

- Epic #551 のうち、Keycloak導入(#559)・api-gateway新設(#560)・identity-service新設(#561)・
  Keycloakユーザー同期(#562)・全サービスのOAuth2 Resource Server化(#563)・
  WebのKeycloakプロバイダ移行(#564)・VSCode拡張のDevice Authorization Grant移行(#565)は
  **すべて完了(CLOSED)済み**。つまり `web`(NextAuth)と`extension`(VSCode拡張)は
  現在すでにKeycloak経由のログインのみを実装しており、`web/src/lib/auth.ts` の
  `providers` 配列には Keycloak プロバイダ以外(旧来の Credentials プロバイダ)は存在しない。
- 一方で、旧認証機構のバックエンドコード(`ApiKeyAuthFilter` / `AuthController` の
  ログイン・2FA・パスワードリセット系エンドポイント / `TwoFactorService` /
  `PasswordResetService` 等、`services/legacy-api` 配下)は**まだ削除されていない**
  ([B8]/#566 が本Issue完了後に着手する担当)。
- したがって「カットオーバー」という言葉が指す実体は、正確には次の2つに分かれる。
  1. **クライアントのログイン経路の切り替え**: これはコードレベルで既に完了している
     (#564/#565)。本手順書が新たに実行する対象ではない。
  2. **既存ユーザーのKeycloakへの登録(データ移行)**: [B4]/#562 で実装された
     `identity-service` の一括移行API (`POST /api/users/migrate-to-keycloak`。
     `UserController`が`@RequestMapping("/api/users")`配下に持つ。`/api/identity/**`は
     `IdentityController`(`/me`等)であり移行系エンドポイントは存在しない点に注意)を
     使って、`keycloak_sub` が未設定の既存ユーザーをKeycloakへ登録し、パスワード再設定を
     要求する。**本手順書が「カットオーバー」として主に実行する対象はこちら。**
- 本プロジェクトはステージング環境と本番環境が分離された構成を持たない。単一の
  `docker-compose.yml` 環境(このリポジトリ自体)がすなわち稼働環境である。そのため
  「ステージング相当の環境」とは、この `docker-compose.yml` 環境を指す
  (別クラスタを新設する場合は `.env` の DBパスワード等を変えた上で同じ手順を使う)。
- 実運用ユーザーはメンテナ本人(s.tonouchi@gmail.com、adminロール)のみであり、
  この実ユーザーは既にKeycloakへ登録済み(#564のテスト時に実施)。それでも、今後
  ユーザーが追加された場合や、バックアップからの再構築時に再現可能な手順として、
  本ドキュメントは「まだ誰もKeycloakへ移行していない」状態からの実行を前提に書く。

### 実行体制

本プロジェクトの規模上、実行者は管理者権限を持つオペレーター1名を想定する。
ロールバック要否の最終判断は実行者自身が行うが、影響がユーザー(メンテナ本人)に
及ぶため、実行前後の周知は必須とする([5. 既存ユーザーのKeycloak登録とパスワードリセット通知](#5-既存ユーザーのkeycloak登録とパスワードリセット通知)、[docs/cutover-user-notice.md](cutover-user-notice.md)参照)。

### 全体の流れ

```text
0. 事前準備(周知・バックアップ保管先確保・ディスク空き容量確認)
   │
1. 事前バックアップ(フェーズA: 稼働中のリハーサル取得)
   │
1.5 管理者トークンの事前取得(ブラウザを使うため reverse-proxy 稼働中に必須)
   │
2. 全サービス停止順序(フェーズ1: 外部トラフィック遮断)
   │
1. 事前バックアップ(フェーズB: 確定スナップショット。以後がロールバック基準点)
   │
3. データ移行スクリプトの実行順序と検証ポイント
   │
5. 既存ユーザーのKeycloak登録とパスワードリセット通知
   │
2. 全サービス停止順序(フェーズ2: 残りサービスの完全停止)
   │
6. 全サービスの起動順序
   │
4. Keycloak realm の import と検証(フルスクラッチ起動での再確認。詳細は4.2参照)
   │
   ├─ 失敗 → 8. ロールバック方針 へ
   │
7. 疎通確認チェックリスト
   │
   ├─ 失敗 → 8. ロールバック方針 へ
   │
完了
```

---

## 1. 事前バックアップ

### 1.1 実行タイミング

バックアップは2回に分けて取得する。

- **フェーズA(T-24h目安、稼働中)**: リハーサル用。本番相当環境が生きた状態のまま
  バックアップ手順自体に問題がないか(コマンドが通るか、所要時間、ディスク容量)を
  確認する。このバックアップは復旧の正式な基準点にはしない。
- **フェーズB(カットオーバー当日、[2. 全サービス停止順序](#2-全サービス停止順序)の
  フェーズ1実行直後)**: `reverse-proxy` / `web` / `gateway` / `api` / 各ドメインサービス /
  `log-writer` を停止し、新規の書き込みが発生しなくなった状態で取得する確定スナップショット。
  **これが [8. ロールバック方針](#8-ロールバック方針) における復旧基準点になる。**
  `mysql` / `keycloak-postgres` / `keycloak` / `identity` はこの時点ではまだ停止しない
  (バックアップの取得自体と、後続の [3](#3-データ移行スクリプトの実行順序と検証ポイント)/[4](#4-keycloak-realm-の-import-と検証)
  で使うため)。

### 1.2 バックアップ対象一覧

既存の `docs/BACKUP_RECOVERY_STRATEGY.md` / `docs/BACKUP_RECOVERY_OPERATIONS.md` および
`/admin/backup` (Web管理画面) ・ `scripts/db-backup.sh` は、いずれも単一スキーマ
(`${MYSQL_DATABASE}` = `lets_blog`)と `generated_images` ボリュームのみを対象にした
マイクロサービス分割(#570 ADR-0004)以前の設計であり、**サービス別スキーマ(`lbs_*`)・
Keycloak用PostgreSQL・その他の名前付きボリュームをカバーしていない**。カットオーバーは
不可逆な操作であるため、この手順書では既存ツールに頼らず、以下を漏れなく個別に
バックアップする。

| 種別 | 対象 | 優先度 | 備考 |
|---|---|---|---|
| MySQL | `lets_blog`(レガシー/未分割スキーマ。`users`テーブルを含む) | 必須 | `identity`/`api`が接続する共有スキーマ |
| MySQL | `lbs_identity` / `lbs_project` / `lbs_content` / `lbs_media` / `lbs_ai` / `lbs_publishing` / `lbs_analytics` / `lbs_platform` / `lbs_log` | 必須 | ADR-0004。`lbs_publishing`/`lbs_platform`は対応サービス未抽出のため空の想定だが、将来の取りこぼし防止のため対象に含める |
| MySQL | WordPressサイトごとの動的DB(`wp_*`等。`wordpress/provision-agent/index.php`がサイト作成時に`CREATE DATABASE`する) | 必須 | **固定スキーマ名の列挙だけでは漏れる**。`mysqldump --all-databases`で一括取得することで担保する(下記1.3参照) |
| PostgreSQL | `keycloak`(Keycloakのrealm/ユーザー/クレデンシャル情報) | 必須 | `lbs-keycloak-postgres`コンテナ |
| Docker Volume | `generated_images` | 必須 | AI生成画像(issue本文で名指し) |
| Docker Volume | `comfyui_models` | 必須 | issue本文で名指し。チェックポイント等 |
| Docker Volume | `wordpress_sites` | 必須 | issue本文で名指し。WordPressサイトのファイル一式 |
| Docker Volume | `bulk_upload_files` | 必須 | 一括アップロード作業ファイル |
| Docker Volume | `comfyui_output` | 推奨 | ComfyUI生成物の一時出力 |
| Docker Volume | `penpot_assets` | 推奨 | カスタムタグAIデザイン生成で使うPenpotアセット |
| Docker Volume | `penpot_postgres` | 推奨 | Penpot自体のDB。Keycloak/認証には非依存だが全停止のタイミングで一緒に取得する |
| Docker Volume | `rabbitmq_data` | 任意 | キューの永続化データ。復旧後は空でも業務継続に支障はない想定だが安全側で対象に含める |
| Docker Volume | `mysql_data` / `keycloak_postgres` | 参考(物理コピー) | 上記の論理ダンプ(mysqldump/pg_dump)が正の復旧手段。物理ボリュームコピーは保険として任意 |

### 1.3 手順

```bash
cd /path/to/lets_blog_server
set -a; source .env; set +a

BACKUP_DIR="backups/cutover-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"

# --- MySQL: 全スキーマを一括ダンプ(動的に作成されるWordPressサイトDBの取りこぼしを防ぐため
#     固定スキーマ名の列挙ではなく --all-databases を使う。system schema(information_schema等)は
#     mysqldumpが自動的に扱いを分けるため明示除外は不要)
docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" lbs-mysql \
  mysqldump --user=root --all-databases --single-transaction --routines --triggers \
  > "$BACKUP_DIR/mysql-all-databases.sql"

# 事後チェック: 想定スキーマがすべて含まれているか確認
# (keycloakはPostgreSQL側であり、MySQLダンプには含まれないため対象外)
for db in lets_blog lbs_identity lbs_project lbs_content lbs_media lbs_ai \
          lbs_publishing lbs_analytics lbs_platform lbs_log; do
  grep -q "CREATE DATABASE.*\`$db\`" "$BACKUP_DIR/mysql-all-databases.sql" \
    || echo "警告: ${db} がダンプに含まれていません" >&2
done
# WordPressサイトDBの件数確認(サイト数と一致するはず。厳密な突合は1.4参照)
# 開発環境には lets_blog_test / lbs_*_test 等のテスト用スキーマも存在するため、
# それらも除外対象に含める(実機確認により、素朴な"lets_blog"だけの除外では
# "lets_blog_test"が漏れて件数確認を誤らせることが判明した)
docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" lbs-mysql \
  mysql --user=root -N -e "SHOW DATABASES;" \
  | grep -Ev '^(information_schema|performance_schema|mysql|sys|lets_blog(_test)?|lbs_.*|keycloak)$'

# --- Keycloak用PostgreSQL
docker exec -e PGPASSWORD="$KEYCLOAK_DB_PASSWORD" lbs-keycloak-postgres \
  pg_dump -U keycloak -d keycloak --format=custom \
  > "$BACKUP_DIR/keycloak-postgres.dump"

# --- Docker named volume群(生ファイル。停止済みのサービスが使うボリュームなので
#     書き込み競合を気にせず tar できる)
for volume in generated_images comfyui_models wordpress_sites bulk_upload_files \
              comfyui_output penpot_assets penpot_postgres rabbitmq_data \
              mysql_data keycloak_postgres; do
  full_volume="$(basename "$(pwd)")_${volume}"
  docker run --rm \
    -v "${full_volume}:/volume:ro" \
    -v "$(pwd)/$BACKUP_DIR:/backup" \
    alpine tar czf "/backup/volume-${volume}.tar.gz" -C /volume .
done

echo "バックアップ完了: $BACKUP_DIR"
du -sh "$BACKUP_DIR"/*
```

`full_volume` の接頭辞(`$(basename "$(pwd)")`、通常は `lets_blog_server`)は
`docker volume ls` の実際の命名(Compose プロジェクト名)と一致することを事前に
`docker volume ls | grep "$(basename "$(pwd)")"` で確認しておくこと
(`COMPOSE_PROJECT_NAME` を明示設定している場合はそちらを使う)。

### 1.4 検証

- 各 `.sql` / `.dump` / `.tar.gz` ファイルが 0 バイトでないこと。
- `mysql-all-databases.sql` に対して上記スキーマ存在チェックが警告なしで通ること。
- WordPressサイトDBの件数が、Web管理画面のサイト一覧の件数と一致すること
  (`SHOW DATABASES` の結果から `information_schema` 等の固定スキーマを除いた件数)。
- `tar tzf volume-<name>.tar.gz | head` で各アーカイブの中身が展開せずに読み取れること
  (アーカイブ破損の早期検知)。
- `keycloak-postgres.dump` は `pg_restore --list keycloak-postgres.dump` でオブジェクト一覧が
  読めること(壊れていないことの確認。実際のリストアはしない)。
- バックアップ一式を、`docker-compose.yml` が動いているホストとは別の場所
  (別ディスク・別マシン・オブジェクトストレージ等)へコピーしておく。同一ディスク上にしか
  無い場合、ディスク障害時にバックアップごと失われる。

### 1.5 管理者トークンの事前取得

**このステップは [2. 全サービス停止順序](#2-全サービス停止順序) のフェーズ1
(`docker compose stop reverse-proxy web`)より前に、必ず実施する。**
[3. データ移行スクリプトの実行順序と検証ポイント](#3-データ移行スクリプトの実行順序と検証ポイント)で使う
業務API用トークン `$ADMIN_TOKEN` は、ブラウザで認可コードを取得する必要があり、
ブラウザからKeycloakへ到達できるのは `reverse-proxy` が生きている今この瞬間だけだからである
(`docker-compose.yml` の `keycloak` サービスはホストポートを一切公開していないため、
reverse-proxy 停止後はブラウザから到達する手段が無い)。

移行APIは `identity-service` の `AdminAuthorizationService.requireAdmin()` で保護されており、
呼び出し元がKeycloak発行JWTの `admin` ロールを持ち、かつそのJWTの `sub` に対応する
ローカルユーザー(`users.keycloak_sub`)が `admin` ロールであることを要求する。
つまり **`sub` と `realm_access.roles` の両方を含むアクセストークン**が必要になる。

#### 使用クライアント: `letsblog-web`(と、その選定理由)

| クライアント | `client.use.lightweight.access.token.enabled` | 判定 |
|---|---|---|
| `admin-cli` | `true` | **使えない**。lightweight access token は `sub` と `realm_access.roles` を含まず、`requireAdmin()` が403になる |
| `security-admin-console` | `true` | 同上 |
| `letsblog-e2e` | なし | E2Eテスト専用のため転用しない(#588)。そもそもstandard flowが無効 |
| `account` | なし | 使えるが、下記の理由で第2候補 |
| **`letsblog-web`** | **なし** | **採用**(理由は下記) |

`letsblog-web` を採用する理由は、**実運用のWeb管理画面と同一のクライアントであり、
発行されるトークンの構成(claim・audience・scope)が通常運用時とまったく同じになる**ため。
カットオーバー当日に「このトークンでなら通る」ことを確認できれば、それはそのまま
通常運用の経路が健全であることの確認にもなる。`account` クライアントでも `sub` /
`realm_access.roles` は得られるが、通常運用では使われない経路であるため第2候補とする。

`letsblog-web` は confidential クライアントで PKCE(`S256`)必須、
redirect_uri は `https://localhost/api/auth/callback/keycloak` の1件のみ
(`keycloak/realm-export.json` で確認できる)。**Keycloakのクライアント設定は一切変更しない。**

#### 手順

```bash
# 1. PKCE の code_verifier / code_challenge を生成する
CODE_VERIFIER=$(openssl rand -base64 96 | tr -d '\n=+/' | cut -c1-96)
CODE_CHALLENGE=$(printf '%s' "$CODE_VERIFIER" \
  | openssl dgst -binary -sha256 | openssl base64 | tr -d '=\n' | tr '/+' '_-')
echo "CODE_VERIFIER=$CODE_VERIFIER"

# 2. 認可URLを組み立てて表示する(このURLをブラウザで開く)
KEYCLOAK_WEB_CLIENT_SECRET=$(grep -m1 '^KEYCLOAK_WEB_CLIENT_SECRET=' .env | cut -d= -f2-)
cat <<URL
https://localhost/auth/realms/letsblog/protocol/openid-connect/auth?client_id=letsblog-web&response_type=code&scope=openid&redirect_uri=https%3A%2F%2Flocalhost%2Fapi%2Fauth%2Fcallback%2Fkeycloak&state=cutover&code_challenge=${CODE_CHALLENGE}&code_challenge_method=S256
URL
```

ブラウザで上記URLを開き、**letsblog realm の管理者アカウント**(Keycloakへ登録済みで
ローカルDBの `users` にも `admin` ロールで存在するユーザー。例: 実運用のメンテナ本人)で
ログインする。ログイン後、`https://localhost/api/auth/callback/keycloak?code=...&state=cutover`
へリダイレクトされる。

> **`code` の取り出し方(重要)**
> このリダイレクト先は Web(Next.js)の NextAuth コールバックであり、こちらで独自に組み立てた
> `state` に対応する cookie が無いため NextAuth はエラー画面へ遷移する。**エラー画面自体は想定内**で
> あり、必要なのは URL の `code` パラメータだけである。遷移が速くURLバーから読み取れない場合は、
> ブラウザの開発者ツールを開き Network タブの **「Preserve log」を有効にしてから**上記URLを開くと、
> `/api/auth/callback/keycloak?code=...` へのリクエストが記録として残るのでそこから読み取れる。
> なお NextAuth は `state` 検証で失敗した時点で終了しトークンエンドポイントを呼ばない想定であり、
> 認可コードは未使用のまま残るはずである。**ただしこれは実機未検証**
> ([10. 既知の制約・未検証事項](#10-既知の制約未検証事項)参照)。認可コードは1回しか使えないため、
> ここで消費されていると手順3のトークン交換が `invalid_grant` で失敗する。その場合は
> 手順2からやり直したうえで、`account` クライアント(public。redirect_uri は
> `/realms/letsblog/account/*` のワイルドカード)への切り替えを検討する。

```bash
# 3. 認可コードをアクセストークンへ交換する
#    注意: accessCodeLifespan = 60秒。認可コードは取得から60秒以内に交換すること。
#          間に合わなかった場合は手順2のURLをもう一度開くところからやり直す。
AUTH_CODE='<URLから取り出したcodeの値>'

TOKEN_JSON=$(curl -sk -X POST \
  -d "grant_type=authorization_code" \
  -d "client_id=letsblog-web" \
  --data-urlencode "client_secret=${KEYCLOAK_WEB_CLIENT_SECRET}" \
  --data-urlencode "code=${AUTH_CODE}" \
  --data-urlencode "redirect_uri=https://localhost/api/auth/callback/keycloak" \
  --data-urlencode "code_verifier=${CODE_VERIFIER}" \
  https://localhost/auth/realms/letsblog/protocol/openid-connect/token)

export ADMIN_TOKEN=$(printf '%s' "$TOKEN_JSON" | jq -r .access_token)
export ADMIN_REFRESH_TOKEN=$(printf '%s' "$TOKEN_JSON" | jq -r .refresh_token)
```

```bash
# 4. トークンの中身を検証する(ここを通らないと3章で403になる)
printf '%s' "$ADMIN_TOKEN" | cut -d. -f2 | tr '_-' '/+' \
  | awk '{ while (length($0) % 4) $0 = $0 "="; print }' | base64 -d 2>/dev/null \
  | jq '{sub, realm_roles: .realm_access.roles, azp, exp}'
# 期待: sub が非null、realm_roles に "admin" が含まれる
#       (admin-cli の lightweight access token ではこの2つが欠落する。それが本手順の理由)
```

`sub` が `null` だったり `realm_access.roles` が存在しない場合は、`admin-cli` など
lightweight access token が有効なクライアントを使ってしまっている。手順2からやり直す。

#### トークンの持ち回りと有効期限

`$ADMIN_TOKEN` / `$ADMIN_REFRESH_TOKEN` は `export` したうえで、
[3.3](#33-移行実行) / [3.4](#34-検証ポイント) まで **同一のシェルで作業を続ける**こと。
シェルを切り替える場合は、値をオペレーターの手元(パスワードマネージャ等)へ一時退避する。

> **トークンの実値を [9. 実行記録](#9-実行記録) やGitへ残さないこと。**
> 実行記録に書くのは「取得した/リフレッシュした」という事実と時刻だけにする。

`keycloak/realm-export.json` の実値は次のとおり。

| 設定 | 値 | 意味 |
|---|---|---|
| `accessTokenLifespan` | 300秒(5分) | アクセストークンの寿命 |
| `accessCodeLifespan` | 60秒(1分) | 認可コードの交換期限 |
| `ssoSessionIdleTimeout` | 1800秒(30分) | 無操作でSSOセッションが失効 = **リフレッシュ間隔の上限** |
| `ssoSessionMaxLifespan` | 36000秒(10時間) | SSOセッションの絶対上限(実質的な制約にはならない) |
| `revokeRefreshToken` | `false` | リフレッシュトークンのローテーション失効なし |

**アクセストークンの5分では足りない前提で進めること。** トークン取得 → フェーズ1停止 →
フェーズBバックアップ(ボリュームのtarを含む) → [3.3](#33-移行実行) → [3.4](#34-検証ポイント)
の一連は、移行対象ユーザー数とボリュームサイズ次第で容易に5分を超える。
そのため [3.1](#31-管理者トークンのリフレッシュ) のリフレッシュ手順を、
**3.3 の直前と 3.4 の直前で毎回**実行してから使う(401/403 を踏んでから対処するのではなく先回りする)。

**30分の制約**: `ssoSessionIdleTimeout` が1800秒であるため、リフレッシュの間隔が30分を超えると
SSOセッションごと失効する。そうなるとブラウザ(= reverse-proxy)が必要な手順1〜3からやり直す
ことになるが、その時点では reverse-proxy は停止済みでカットオーバー中には回復できない。
**3章の作業が30分以上中断する見込みになった場合は、トークンの再取得を試みるのではなく
[8.2 ロールバックの判断基準](#82-ロールバックの判断基準)へ移ること。**

---

## 2. 全サービス停止順序

`docker-compose.yml` の `depends_on` グラフ([docs/DOCKER_COMPOSE_ARCHITECTURE.md](DOCKER_COMPOSE_ARCHITECTURE.md)参照)に
沿って、依存される側(基盤)を後に、依存する側(フロント)を先に止める。2段階に分ける。

### フェーズ1: 外部トラフィック遮断

実行タイミングは [1.1](#11-実行タイミング) のフェーズAのバックアップ後、
[3. データ移行スクリプトの実行順序と検証ポイント](#3-データ移行スクリプトの実行順序と検証ポイント)〜
[4. Keycloak realm の import と検証](#4-keycloak-realm-の-import-と検証)の作業前。

> **前提: [1.5 管理者トークンの事前取得](#15-管理者トークンの事前取得)を先に完了していること。**
> `$ADMIN_TOKEN` の取得にはブラウザからKeycloakへ到達できる必要があり、
> このコマンドで `reverse-proxy` を止めるとその手段が失われる。取得前に停止してしまった場合は、
> `docker compose start reverse-proxy` で一時的に再開して取得し直す(その間は外部からの
> アクセスが再び通る点に注意する)。

新規リクエストを止めつつ、移行作業に必要な `mysql` / `rabbitmq` / `keycloak-postgres` /
`keycloak` / `identity` / `gateway` は稼働させたままにする(`gateway`はreverse-proxy経由
でしか外部到達できないため、reverse-proxy停止後は内部通信専用になり安全に稼働継続できる)。

```bash
docker compose stop reverse-proxy web
```

この時点で外部(ブラウザ・VSCode拡張)からのアクセスは全経路で遮断される
(`reverse-proxy` が唯一の外部窓口。[docs/DOCKER_COMPOSE_ARCHITECTURE.md](DOCKER_COMPOSE_ARCHITECTURE.md)参照)。

続けて [1.フェーズB](#11-実行タイミング) の確定バックアップを取得し、
[3. データ移行スクリプトの実行順序と検証ポイント](#3-データ移行スクリプトの実行順序と検証ポイント)〜
[5. 既存ユーザーのKeycloak登録とパスワードリセット通知](#5-既存ユーザーのkeycloak登録とパスワードリセット通知)を実行する。

### フェーズ2: 残りサービスの完全停止

実行タイミングは [5. 既存ユーザーのKeycloak登録とパスワードリセット通知](#5-既存ユーザーのkeycloak登録とパスワードリセット通知)
完了後、[6. 全サービスの起動順序](#6-全サービスの起動順序)の前。

全サービスを一度完全に落としてから再起動することで、設定変更(realm-export.json等)や
コンテナの状態を含めクリーンな状態から起動し直し、[7.疎通確認](#7-疎通確認チェックリスト)を
「フレッシュ起動が成功する」ことの確認も兼ねさせる。

```bash
# フロント→基盤の順(depends_onの逆順)
docker compose stop gateway
docker compose stop api project analytics content ai media
docker compose stop log-writer
docker compose stop identity
docker compose stop keycloak
docker compose stop keycloak-postgres mysql rabbitmq docker-socket-proxy

# カットオーバーに直接関与しない補助サービスも、全停止の一貫性のため合わせて停止する
docker compose stop wordpress phpmyadmin
docker compose stop penpot-frontend penpot-backend penpot-exporter penpot-mcp \
  penpot-postgres penpot-valkey penpot-mailcatch
docker compose stop comfyui plantuml drawio

docker compose ps   # 全コンテナがExited/Stoppedになっていることを確認
```

---

## 3. データ移行スクリプトの実行順序と検証ポイント

対象は [B4]/#562 で実装済みの `identity-service` のKeycloak一括移行API。
[フェーズ1](#フェーズ1-外部トラフィック遮断)実行後、
`gateway` / `identity` / `keycloak` / `mysql` はまだ稼働している状態で行う。

**この時点では `reverse-proxy`(唯一の外部窓口)は停止済みであり、ホストから
`https://localhost/...` へアクセスすることはできない([6. 全サービスの起動順序](#6-全サービスの起動順序)まで再起動しない)。
そのため本章のAPI呼び出しはすべて、`lbs-net` に参加している別コンテナ(`gateway`。
実際に`curl`が入っている。healthcheckでも使用)から `docker exec` して
コンテナ名解決(`gateway:8080` 自身宛、あるいは `keycloak:8080`)で叩く形に統一する。
`https://localhost/...` は使わない。**

**この制約があるため、ブラウザ操作を必要とする `$ADMIN_TOKEN` の取得は本章では行えない。
reverse-proxy がまだ稼働している [1.5 管理者トークンの事前取得](#15-管理者トークンの事前取得)で
取得済みであることが本章の前提であり、本章で行うのは
[3.1](#31-管理者トークンのリフレッシュ) の `refresh_token` グラントによる再発行だけである
(リフレッシュはブラウザを必要としないため `docker exec` に収まる)。**

### 3.1 管理者トークンのリフレッシュ

業務API用の `$ADMIN_TOKEN` は、reverse-proxy がまだ稼働していた
[1.5 管理者トークンの事前取得](#15-管理者トークンの事前取得)で取得済みである。
**ここで新規に取得することはできない**(ブラウザからKeycloakへ到達できないため)。

`accessTokenLifespan` は300秒(5分)しかなく、1.5 の取得からフェーズ1停止・フェーズBバックアップを
経てここへ到達する頃には失効している可能性が高い。そのため
**[3.3](#33-移行実行) の直前と [3.4](#34-検証ポイント) の直前で、毎回この手順でリフレッシュしてから使う。**
リフレッシュはブラウザを必要とせず、コンテナ内から実行できるため reverse-proxy 停止後でも成立する。

**`lbs-keycloak` コンテナのイメージには `curl`/`wget` が入っていない
(`docker-compose.yml` の `keycloak` サービス定義のコメント参照)ため、
`docker exec lbs-keycloak curl ...` は使わないこと。** 代わりに、同じ `lbs-net` 上にいて
実際に `curl` が入っている `gateway` コンテナ(`services/gateway/Dockerfile` でhealthcheck用に
インストール済み)から、コンテナ名解決(`keycloak:8080`)でリクエストを送る。

```bash
# letsblog-web は confidential クライアントのため client_secret も必要
KEYCLOAK_WEB_CLIENT_SECRET=$(grep -m1 '^KEYCLOAK_WEB_CLIENT_SECRET=' .env | cut -d= -f2-)

TOKEN_JSON=$(docker exec lbs-gateway curl -s \
  -d "grant_type=refresh_token" \
  -d "client_id=letsblog-web" \
  --data-urlencode "client_secret=${KEYCLOAK_WEB_CLIENT_SECRET}" \
  --data-urlencode "refresh_token=${ADMIN_REFRESH_TOKEN}" \
  http://keycloak:8080/auth/realms/letsblog/protocol/openid-connect/token)

export ADMIN_TOKEN=$(printf '%s' "$TOKEN_JSON" | jq -r .access_token)
export ADMIN_REFRESH_TOKEN=$(printf '%s' "$TOKEN_JSON" | jq -r .refresh_token)

# 取得できたことの確認(sub と realm_access.roles が載っていること)
printf '%s' "$ADMIN_TOKEN" | cut -d. -f2 | tr '_-' '/+' \
  | awk '{ while (length($0) % 4) $0 = $0 "="; print }' | base64 -d 2>/dev/null \
  | jq '{sub, realm_roles: .realm_access.roles, exp}'
```

`access_token` が `null` で返る場合、`ssoSessionIdleTimeout`(1800秒=30分)を超えて
SSOセッションが失効している。**この状態はカットオーバー中には回復できない**
(再取得にはブラウザ = reverse-proxy が必要)。
[8.2 ロールバックの判断基準](#82-ロールバックの判断基準)へ移ること。

`$KC_BOOTSTRAP_TOKEN`(master realm の bootstrap admin トークン。[3.4](#34-検証ポイント) と
[4.2](#42-検証手順)で使う)は本手順の対象外である。あちらは password グラントで随時取得でき、
reverse-proxy 停止中でも `docker exec` から取得できる。

### 3.2 移行前の状態確認

```bash
docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" lbs-mysql \
  mysql --user="$MYSQL_USER" "$MYSQL_DATABASE" \
  -e "SELECT COUNT(*) AS total, SUM(keycloak_sub IS NULL) AS unmigrated FROM users;"
```

### 3.3 移行実行

> **直前に [3.1 管理者トークンのリフレッシュ](#31-管理者トークンのリフレッシュ)を実行すること。**
> `accessTokenLifespan` は300秒しかなく、フェーズBバックアップを挟んだ時点で
> [1.5](#15-管理者トークンの事前取得)のトークンは失効している可能性が高い。
> 401/403を踏んでから対処するのではなく、先回りしてリフレッシュしてから実行する。

`gateway` はまだ `lbs-net` 内部でのみ到達可能な状態(reverse-proxy停止中)なので、
`gateway` コンテナ自身に `docker exec` し、`localhost:8080`(=`gateway`自身の待受ポート)宛に
リクエストする。`gateway`のルーティング設定(`app.gateway.routes`)により
`/api/users/**` は内部で `identity` サービスへ転送される
(reverse-proxy稼働時に外部から `https://localhost/api/users/...` を叩いた場合と同じ経路)。

```bash
docker exec lbs-gateway curl -s -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  http://localhost:8080/api/users/migrate-to-keycloak \
  -d '{}' | jq .
```

(`userIds` を省略した場合、`keycloak_sub` 未設定の全ユーザーが対象になる。
`UserService.migrateToKeycloak` は対象ユーザーごとに Keycloak へのユーザー作成 →
`keycloak_sub` の書き戻し → パスワード再設定メール送信、を行い、成功/失敗の内訳
(`MigrationSummaryResponse`)を返す。)

### 3.4 検証ポイント

> **ここでも直前に [3.1 管理者トークンのリフレッシュ](#31-管理者トークンのリフレッシュ)を実行すること。**
> [3.3](#33-移行実行) の移行処理自体が5分を超えることがあり、その場合このステップに入る時点で
> `$ADMIN_TOKEN` は失効している。

- レスポンスの `failed` が空であること。空でない場合、対象ユーザーIDとエラー内容を記録し、
  [8. ロールバック方針](#8-ロールバック方針)の判断基準に照らして続行可否を判断する。
- 移行後、`users.keycloak_sub` が全件で非NULLになっていること(3.2と同じクエリを再実行し
  `unmigrated = 0` を確認)。
- 移行対象と同数のユーザーがKeycloakへ作成されていること。この時点では
  reverse-proxyが停止中でKeycloak管理コンソール(ブラウザ)へはアクセスできないため、
  `gateway` コンテナから Keycloak Admin REST API を `docker exec` 経由で叩いて件数を確認する
  (ブラウザでの目視確認は、[6. 全サービスの起動順序](#6-全サービスの起動順序)でreverse-proxyを
  再起動した後に改めて行ってもよい)。

  **注意: `/auth/admin/realms/letsblog/users` はKeycloak自身のAdmin REST APIであり、
  letsblog realmの業務用ロール(`admin`。`identity-service`の`requireAdmin()`が見るロール)
  とは別物である。letsblog realmの`admin`ロール(`keycloak/realm-export.json`参照。
  `composite: false`で`realm-management`クライアントロールを持たない)から発行された
  [1.5](#15-管理者トークンの事前取得)で取得した `$ADMIN_TOKEN` では権限不足(403)になる。
  このAPIを呼ぶには、`docker-compose.yml` の `keycloak` サービスに設定されている
  bootstrap admin(`KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD`。
  実体は `.env` の `KEYCLOAK_ADMIN_USERNAME` / `KEYCLOAK_ADMIN_PASSWORD`)でmaster realmから
  トークンを取得する必要がある(bootstrap adminはデフォルトで全realmに対する管理権限を持つ)。
  以降、このトークンを `$KC_BOOTSTRAP_TOKEN` と表記し、`$ADMIN_TOKEN`(業務API用)とは
  明確に区別する。**

  ```bash
  KC_BOOTSTRAP_TOKEN=$(docker exec lbs-gateway curl -s \
    -d "client_id=admin-cli" -d "grant_type=password" \
    -d "username=$KEYCLOAK_ADMIN_USERNAME" -d "password=$KEYCLOAK_ADMIN_PASSWORD" \
    http://keycloak:8080/auth/realms/master/protocol/openid-connect/token \
    | jq -r .access_token)

  docker exec lbs-gateway curl -s -H "Authorization: Bearer $KC_BOOTSTRAP_TOKEN" \
    "http://keycloak:8080/auth/admin/realms/letsblog/users?max=1000" \
    | jq 'length'
  # 期待: 3.2で確認した unmigrated の件数と一致する
  ```

- 孤児検出の整合性確認として `reconcile-keycloak` を実行し、`deactivated` が空であること
  (直後に実行しているため、Keycloak側に存在しないユーザーは無いはず)。

```bash
docker exec lbs-gateway curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/api/users/reconcile-keycloak | jq .
```

- 移行された各ユーザー宛にパスワード再設定メールが実際に届いていること
  (開発環境ではMailCatcher `http://localhost:1080` で確認できる。
  [keycloak/README.md](../keycloak/README.md)参照)。

---

## 4. Keycloak realm の import と検証

### 4.1 realm定義の反映方法(重要な既知の制約)

realm定義は `keycloak/realm-export.json` をGit管理し、`keycloak` コンテナが
`start --import-realm` で起動時に自動importする(`keycloak/README.md`参照)。

**Keycloakの `--import-realm` は「realmが存在しない場合のみ新規importする」動作であり、
既にrealmが存在する状態(=Keycloakのデータボリュームが既に初期化済みの状態)で
`realm-export.json` の内容を変更しても、コンテナ再起動だけでは変更が反映されない**
(ログに `already exists. Import skipped` と出る)。したがって、
realmの設定(クライアント追加、ロール変更等)を今回のカットオーバーに合わせて更新した
場合は、以下のいずれかが必要になる。

- 変更内容をKeycloak管理コンソールで手動反映し、`keycloak/README.md` の
  「`realm-export.json` の再生成手順」に従ってエクスポートし直し、Gitへコミットする
  (通常の運用時の変更手順。データを失わない)。
- 検証環境等でrealmを完全にリセットしたい場合のみ、`keycloak_postgres` ボリュームを
  削除してから起動し直す(**Keycloak側の全ユーザー・パスワード・セッションが失われる
  破壊的操作**。カットオーバー当日には行わない)。

### 4.2 検証手順

[フェーズ2](#フェーズ2-残りサービスの完全停止)の完全停止後、
[6. 全サービスの起動順序](#6-全サービスの起動順序)で起動して確認する
(通常運用ではKeycloakは既に稼働し続けているため、この検証は主に「フルスクラッチ起動でも
正しく動く」ことの確認になる)。

```bash
docker compose logs keycloak | grep -i realm
# 期待: "Realm 'letsblog' imported" (初回) または
#       "already exists. Import skipped" (2回目以降。想定内)

curl -sk https://localhost/auth/realms/letsblog/.well-known/openid-configuration | jq .issuer
# 期待: "https://localhost/auth/realms/letsblog"

# クライアント/ロール一覧の確認にはKeycloak Admin REST APIへのアクセスが必要。
# letsblog realmの業務用`admin`ロール([1.5](#15-管理者トークンの事前取得)の$ADMIN_TOKEN)は
# realm-management権限を持たないため使えない(3.4の注意書き参照)。ここでも
# bootstrap admin(master realm)のトークンが必要。
#
# また、このタイミングは[フェーズ2](#フェーズ2-残りサービスの完全停止)の全停止
# ([6. 全サービスの起動順序](#6-全サービスの起動順序))を経た後であり、3章で使った
# トークンは`accessTokenLifespan`(realm-export.jsonで300秒=5分)を超えて失効している
# 可能性が高い。ここで必要なのは$KC_BOOTSTRAP_TOKEN(master realm)であり、
# password グラントで随時取得できるため、使い回さず必ずここで新規に取得し直す。
#
# なお業務API用の$ADMIN_TOKENは、この時点ではreverse-proxyが再起動済み
# ([6. 全サービスの起動順序](#6-全サービスの起動順序))であれば
# [1.5](#15-管理者トークンの事前取得)と同じ手順で取り直せる。まだ再起動前で、かつ
# $ADMIN_REFRESH_TOKENが生きていれば[3.1](#31-管理者トークンのリフレッシュ)でリフレッシュする。
KC_BOOTSTRAP_TOKEN=$(curl -sk \
  -d "client_id=admin-cli" -d "grant_type=password" \
  -d "username=$KEYCLOAK_ADMIN_USERNAME" -d "password=$KEYCLOAK_ADMIN_PASSWORD" \
  https://localhost/auth/realms/master/protocol/openid-connect/token \
  | jq -r .access_token)

curl -sk -H "Authorization: Bearer $KC_BOOTSTRAP_TOKEN" \
  https://localhost/auth/admin/realms/letsblog/clients \
  | jq -r '.[].clientId'
# 期待: letsblog-web, letsblog-vscode, letsblog-services を含む

curl -sk -H "Authorization: Bearer $KC_BOOTSTRAP_TOKEN" \
  https://localhost/auth/admin/realms/letsblog/roles \
  | jq -r '.[].name'
# 期待: admin, editor, viewer を含む
```

---

## 5. 既存ユーザーのKeycloak登録とパスワードリセット通知

[3. データ移行スクリプトの実行順序と検証ポイント](#3-データ移行スクリプトの実行順序と検証ポイント)の
実行によりユーザー登録とパスワード再設定メール送信自体は完了している。本ステップは、
それに加えて利用者への明示的な周知を行う。

1. [docs/cutover-user-notice.md](cutover-user-notice.md) の文面を使い、カットオーバー実施前に
   予告、実施後に完了報告を送付する(メール・チャット等、実際に利用者へ届く手段)。
2. 周知する内容(3点、Issue本文の要求どおり):
   - 全ユーザーがパスワード再設定を必要とすること(Keycloakからのパスワード再設定メールに
     従って新しいパスワードを設定する)
   - VSCode拡張の再ログインが必要なこと(コマンドパレットから `Let's Blog: Login` を実行し、
     Device Authorization Grantで再認可する)
   - 切り替え中のダウンタイム見込み(実測値は[9. 実行記録](#9-実行記録)に記入し、以降は
     実測値を周知文に反映する)
3. パスワード再設定メールが届かない/紛失した場合の代替経路として、Keycloak管理コンソールから
   管理者が対象ユーザーのパスワードリセットメールを再送できることを伝える
   (`https://localhost/auth/admin/master/console/#/letsblog/users` → 対象ユーザー →
   Credentials タブ → Reset password)。**このURLは reverse-proxy 経由での外部アクセスを
   前提としており、[6. 全サービスの起動順序](#6-全サービスの起動順序)でreverse-proxyを
   再起動した後にのみ到達可能。**カットオーバー当日、reverse-proxy再起動前([3. データ移行
   スクリプトの実行順序と検証ポイント](#3-データ移行スクリプトの実行順序と検証ポイント)の
   段階)で同等の操作が必要になった場合は、[3.4](#34-検証ポイント)と同じ
   bootstrap adminトークン(`$KC_BOOTSTRAP_TOKEN`)による `docker exec` 経由のKeycloak
   Admin REST API呼び出しで代替する(letsblog realmの業務用`$ADMIN_TOKEN`では
   権限不足になるため使えない点に注意)。

---

## 6. 全サービスの起動順序

[2.フェーズ2](#フェーズ2-残りサービスの完全停止)で全停止した状態から、
`depends_on` の依存方向(基盤→フロント)に沿って起動する。`docker compose up -d` は
`depends_on` + `condition: service_healthy` を解決して自動的にこの順序で起動するため、
通常は一括実行でよい。本項では、途中で問題を切り分けやすいよう明示的な段階起動も示す。

```bash
# 一括起動(depends_onの解決に任せる。通常はこれでよい)
docker compose up -d

# --- 明示的な段階起動が必要な場合(切り分け用) ---
# 1. 基盤
docker compose up -d mysql rabbitmq keycloak-postgres docker-socket-proxy
docker compose up -d --wait mysql rabbitmq keycloak-postgres

# 2. Keycloak
docker compose up -d keycloak
docker compose up -d --wait keycloak

# 3. identity(Keycloak Admin APIを使うため keycloak 起動後)
docker compose up -d identity
docker compose up -d --wait identity

# 4. ドメインサービス群(mysql/keycloak/identity/rabbitmq に依存)
docker compose up -d media ai content analytics project
docker compose up -d --wait media ai content analytics project

# 5. legacy-api(media/ai/content/analyticsに依存) と log-writer
docker compose up -d api log-writer
docker compose up -d --wait api log-writer

# 6. gateway(上記すべてに依存)
docker compose up -d gateway
docker compose up -d --wait gateway

# 7. web、最後にreverse-proxy(唯一の外部窓口)
docker compose up -d web
docker compose up -d reverse-proxy

# 補助サービス
docker compose up -d wordpress phpmyadmin
docker compose up -d penpot-postgres penpot-valkey penpot-mailcatch
docker compose up -d penpot-backend penpot-exporter penpot-mcp penpot-frontend
docker compose up -d comfyui plantuml drawio

docker compose ps   # 全サービスがhealthy/runningであることを確認
```

---

## 7. 疎通確認チェックリスト

`https://localhost` (自己署名証明書。`scripts/generate-certs.sh` 参照)経由で、Issue本文が
要求する4項目を含め確認する。

- [ ] **Webログイン**: `https://localhost` にアクセスし、Keycloakのログイン画面へ
      リダイレクトされる → 移行済みユーザーの新パスワードでログインできる →
      ダッシュボードが表示される
- [ ] **VSCodeログイン**: VSCode拡張のコマンドパレットから `Let's Blog: Login` を実行し、
      Device Authorization Grantのユーザーコード画面が表示される → ブラウザで認可 →
      拡張側でログイン完了が表示される
- [ ] **記事公開**: 既存サイトの記事を1件編集・保存し、WordPressサイトへの公開操作が
      成功する(公開後のURLで実際にコンテンツが表示されることまで確認する)
- [ ] **画像生成**: AI画像生成機能を1回実行し、ComfyUI経由で画像が生成され
      `generated_images` に保存されることを確認する
- [ ] **サービス間通信**: `docker compose ps` で `gateway` / `identity` /
      各ドメインサービスがすべて `healthy` であること
      (Client Credentials認証([B9]/#567)がKeycloak起動直後でも機能していることの
      間接的な確認)
- [ ] **監査ログ**: 上記の操作(移行実行・ログイン等)が監査ログに記録されていること
      ([B11]/#569 のJWTベースactor解決が機能していることの確認)
- [ ] **旧ログイン経路が誤って使えないこと**: `web` に旧来のCredentialsログインフォームが
      存在しないこと(既に#564で削除済みだが、意図しない復元がないことの確認)
- [ ] **管理者による追加ユーザー操作**: 新規ユーザー作成 → Keycloakへの自動登録 →
      該当ユーザーでのログイン、が一連で成立すること

上記すべてにチェックが入った時点でカットオーバー完了とする。1項目でも失敗した場合は
[8. ロールバック方針](#8-ロールバック方針)の判断基準に従う。

---

## 8. ロールバック方針

### 8.1 どの時点まで戻せるか

**原則として、[1.フェーズB](#11-実行タイミング)で取得した確定バックアップ時点への
全体復旧のみを正式なロールバック手段とする。部分復旧(移行したユーザーのうち一部だけを
Keycloakから取り消す、等)は推奨しない。**

理由は次の2点。

1. `identity-service` のユーザー移行(3章)は「Keycloakへのユーザー作成」と
   「ローカル`users.keycloak_sub`の書き込み」の2箇所にまたがる操作であり、
   片方だけを個別に取り消すとローカルとKeycloakの不整合(孤児ユーザー)が生じる。
2. **より重要な制約として、Webフロントエンド(#564)・VSCode拡張(#565)は既に
   旧来のログインUI/フロー自体を削除済みである。** そのため「データだけ」を
   カットオーバー前の状態に戻しても、ユーザーはログインする手段を失う
   (Webにはログインフォームが無く、拡張には`letsBlog.setApiKey`的なAPIキー入力コマンドが
   もう存在しない)。旧ログイン経路そのものを機能させたい場合は、データのロールバックに加えて
   **アプリケーションのデプロイ物自体を#564/#565マージ前のコミット/イメージへ戻す
   コードロールバックが必須**になる。

したがって実務上のロールバックは次の2段階で考える。

| ロールバックの種類 | 内容 | いつ使うか |
|---|---|---|
| **データのみのロールバック** | [1.フェーズB](#11-実行タイミング)のMySQL/Keycloak PostgreSQL/各種volumeを復元。アプリのコードは現行のまま | 移行処理自体は失敗したが、Keycloakでのログインという仕組み自体は健全に機能する場合(例: 一部ユーザーの移行が失敗した、realm importに問題があった等)。復元後、原因を修正して[3](#3-データ移行スクリプトの実行順序と検証ポイント)からやり直す |
| **フルロールバック(データ+コード)** | 上記に加え、`git checkout <#564/#565マージ前のコミット>` (または該当タグの旧イメージ)でアプリを再デプロイし、旧ログインUI/フローを復元する | Keycloak自体に起因する深刻な問題(realmが壊れた、OIDC経路全体が機能しない等)で、ユーザーが当面ログインする手段を確保する必要がある場合。**この手順書の対象範囲では、この判断が下るケースは「realm importの検証([4.2](#42-検証手順))が失敗し、かつ短時間で復旧できない」場合を想定する** |

いずれの場合も、[7. 疎通確認チェックリスト](#7-疎通確認チェックリスト)がすべて緑になるまでは
カットオーバー完了と見なさない。

### 8.2 ロールバックの判断基準

以下のいずれかに該当する場合、ロールバックを検討する。

- [3.4](#34-検証ポイント)のユーザー移行検証で `failed` が発生し、対象が全ユーザーの
  相当割合(目安: 過半数、または唯一の管理者アカウントを含む)に及ぶ
- [4.2](#42-検証手順)のrealm検証(`.well-known`、クライアント一覧、ロール一覧)のいずれかが
  失敗し、[keycloak/README.md](../keycloak/README.md)の手順で30分以内に復旧できない
- [7. 疎通確認チェックリスト](#7-疎通確認チェックリスト)のうち「Webログイン」または
  「VSCodeログイン」が失敗し、原因調査が30分以内に完了しない
  (唯一のログイン経路が機能しない = サービス全体が使用不能と同義のため)
- [6. 全サービスの起動順序](#6-全サービスの起動順序)実行後、`gateway` / `identity` /
  いずれかのドメインサービスが `unhealthy` のまま15分以上回復しない
- 想定外のデータ破損・欠損が確認された場合(件数突合の不一致等)

軽微な問題(例: 一部の補助サービス(Penpot等)が起動しない、監査ログの表示が一部欠ける等、
[認証・ログイン・記事公開・画像生成]の中核機能に影響しないもの)は、ロールバックせず
別Issueとして起票し、カットオーバー自体は完了扱いとする。

### 8.3 ロールバック手順

**データのみのロールバック**

```bash
# 1. 全サービス停止(2章フェーズ1+フェーズ2と同じ手順。フェーズ1のreverse-proxy/webと
#    フェーズ2の残り全サービスを合わせて完全停止する)
docker compose stop reverse-proxy web
docker compose stop gateway
docker compose stop api project analytics content ai media
docker compose stop log-writer
docker compose stop identity
docker compose stop keycloak
docker compose stop keycloak-postgres mysql rabbitmq docker-socket-proxy
docker compose stop wordpress phpmyadmin
docker compose stop penpot-frontend penpot-backend penpot-exporter penpot-mcp \
  penpot-postgres penpot-valkey penpot-mailcatch
docker compose stop comfyui plantuml drawio

# 2. MySQL復元(全データベースを一度作り直してから流し込む)
docker compose up -d mysql
docker compose up -d --wait mysql
docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" lbs-mysql \
  mysql --user=root < "$BACKUP_DIR/mysql-all-databases.sql"

# 3. Keycloak PostgreSQL復元(docker execに-iが無いと標準入力がコンテナへ渡らず、
#    "input file is too short"エラーで失敗する。実機リハーサルで実際に確認した)
docker compose up -d keycloak-postgres
docker compose up -d --wait keycloak-postgres
docker exec -i -e PGPASSWORD="$KEYCLOAK_DB_PASSWORD" lbs-keycloak-postgres \
  pg_restore -U keycloak -d keycloak --clean --if-exists \
  < "$BACKUP_DIR/keycloak-postgres.dump"

# 4. Docker volume群の復元(必要なもののみ。generated_images/comfyui_models/
#    wordpress_sites/bulk_upload_filesはカットオーバー中に書き込みが発生しない想定のため
#    通常は復元不要だが、念のため手順を残す)
for volume in generated_images comfyui_models wordpress_sites bulk_upload_files; do
  full_volume="$(basename "$(pwd)")_${volume}"
  docker run --rm -v "${full_volume}:/volume" -v "$(pwd)/$BACKUP_DIR:/backup" \
    alpine sh -c "rm -rf /volume/* /volume/..?* /volume/.[!.]* 2>/dev/null; \
                   tar xzf /backup/volume-${volume}.tar.gz -C /volume"
done

# 5. 全サービス再起動(6章と同じ手順)
docker compose up -d
```

**フルロールバック(データ+コード)**

上記「データのみのロールバック」に加えて、アプリケーションのデプロイ物を戻す。

```bash
# #564(Web)・#565(VSCode拡張)マージ前の状態へ戻す例
git log --oneline --all | grep -i "564\|565"   # 対象コミットを特定
git checkout <#564マージ前のコミットハッシュ>

# イメージを再ビルドして起動し直す
docker compose build web api
docker compose up -d
```

VSCode拡張については、配布済みの `.vsix` を旧バージョンへ差し戻す(あるいはWeb管理画面の
拡張機能ダウンロード機能が指すブランチ/コミットを戻す)必要がある。

### 8.4 所要時間の見積もり

| 作業 | 見積もり | 根拠 |
|---|---|---|
| データのみのロールバック(2〜5) | 20〜40分 | MySQL全体ダンプの復元時間はデータ量に依存(本プロジェクトの現行データ規模は小さいため`docs/BACKUP_RECOVERY_STRATEGY.md`のRTO目安30分以内を参考値とする)。実測値は未取得(10章参照) |
| フルロールバック(上記+コード差し戻し) | 40〜70分 | データのみのロールバックに、`docker compose build` の再ビルド時間(数分〜10分程度、対象サービス数次第)と拡張機能の差し戻し作業を加算 |
| 判断そのものに要する時間 | 別途最大30分 | 8.2の判断基準に「30分以内に復旧できない場合」を含めているため、判断自体にこの上限を設ける |

**いずれも実測値ではなく見積もりである。実機での計測が完了していない点は[10章](#10-既知の制約未検証事項)に明記する。**

---

## 9. 実行記録

実際にカットオーバーおよびロールバックを実行した際は、このセクションに実測値を追記する。

2026-08-27、本Issue(#591)のQA工程で、ユーザー立ち会いのもと本docker-compose環境
(実運用ユーザーはメンテナ本人のみ)に対して実際に通し実行した。

| 項目 | 実測値 | 実施日 | 備考 |
|---|---|---|---|
| 1章 バックアップ所要時間 | mysqldump: 約0.4秒 / pg_dump: 数秒未満 | 2026-08-27 | データ量が小さい現行環境での実測値。本番相当のデータ量では別途計測が必要 |
| 2章フェーズ1(トラフィック遮断) | 1秒未満 | 2026-08-27 | `docker compose stop reverse-proxy web` |
| 3章(ユーザー移行) | 未実施 | | 管理者トークンの取得(当時の3.1。現在は[1.5](#15-管理者トークンの事前取得))にletsblog realmの人間管理者(実運用ユーザー本人)のパスワードが必要で、AIエージェントには付与していないため未実施。かつ実行時点で未移行ユーザーが0件(3人全員移行済み。うち実運用は本人1名、残り2名はロール確認用の開発シードアカウント)のため、実施しても新規移行は発生しない状態だった。次回、未移行ユーザーが存在する状況で改めて実施計測が必要 |
| 4章(realm検証) | 全項目成功(所要は7章と合わせて数秒程度) | 2026-08-27 | `.well-known`/clients一覧/roles一覧すべて期待通り。realm importは`already exists. Import skipped`(想定通り) |
| 2章フェーズ2〜6章(全停止→全起動) | 停止22秒 + 起動41秒 = 63秒 | 2026-08-27 | 全28サービス。本開発環境はイメージビルド済み・データ量小のため高速。実測は目安として扱うこと |
| 7章(疎通確認) | 自動検証可能な項目はPASS。ブラウザ操作を要する項目は未実施 | 2026-08-27 | サービス間通信(全healthy)・旧ログイン経路の不在・APIの認可強制(未認証で403)を確認。Webログイン/VSCodeログイン/記事公開/画像生成はブラウザ・VSCode拡張の対話操作が必要で本セッションでは未実施 |
| 総ダウンタイム(フェーズ1開始〜7章完了、カットオーバー本番想定分) | 約3分52秒(14:02:16〜14:06:08) | 2026-08-27 | 3章(ユーザー移行)を除く、フェーズ1停止〜全停止〜全起動までの実測。停止/起動コマンド自体の合計(約64秒)との差分は、healthcheckの安定待ちや§4検証の目視確認等、個別のストップウォッチ計測に含めていない時間 |
| 8章 ロールバック(試行時) | 停止22秒 + MySQL復元15秒 + Keycloak PostgreSQL復元(数秒) + 再起動38秒。復元後、users件数(3件)・keycloak_sub未設定0件・KeycloakユーザーAPI件数(3件)・WordPress動的DB(2件)がいずれも復元前と一致することを確認 | 2026-08-27 | データのみのロールバックを実施。機械的な所要時間の合計は約75秒(+判断・確認に要する時間は別途)。このセッションでは`pg_restore`コマンドがAIエージェントの権限上ブロックされ、ユーザー本人に手動実行してもらう待ち時間が生じたため、8.4節の見積もりはこの機械的所要時間を参照すること |

---

## 10. 既知の制約・未検証事項

- **2026-08-27、本Issue(#591)のQA工程でユーザー立ち会いのもと実機通し実行を行った
  ([9. 実行記録](#9-実行記録)参照)。バックアップ→フェーズ1停止→フェーズ2完全停止→
  全サービス起動→realm検証→(データのみの)ロールバック試行→復旧確認、の一連が
  実際に成功した。この過程で本ドキュメントに残っていた以下3件のコマンド不備を発見し、
  修正済み(いずれも机上のレビューでは気づけず、実行して初めて判明したもの)。**
  - §1.3のバックアップ事後チェックが、PostgreSQL側にしか存在しない`keycloak`スキーマを
    MySQLダンプ内に期待しており、常に警告を出す誤りだった(修正済み)。
  - §1.3/§8.3のWordPress動的DB件数確認のgrepパターンが`lets_blog_test`のようなテスト用
    スキーマを除外し損ね、件数を誤らせる誤りだった(修正済み)。
  - §8.3の Keycloak PostgreSQL復元コマンドが `docker exec` に `-i`(標準入力転送)を
    指定しておらず、"input file is too short" エラーで復元自体が失敗する誤りだった
    (修正済み)。
- **一方で、以下は今回のセッションでも実施できておらず、引き続き未検証。**
  - **3章(実際のユーザー移行実行)**: 移行APIのトークン取得
    ([1.5](#15-管理者トークンの事前取得))にletsblog realmの人間管理者(実運用ユーザー本人)の
    パスワードが必要で、AIエージェントには意図的に付与していないため未実施。また実行時点で
    未移行ユーザーが0件だったため、移行対象が存在する状態での実施確認は別途必要。
  - **フルロールバック(データ+コード)**: 今回試行したのは「データのみのロールバック」
    のみ。`docker compose build`からの再デプロイで実際に旧UIのログイン画面が復元できるかは
    未確認。
  - **[7. 疎通確認チェックリスト](#7-疎通確認チェックリスト)のうちブラウザ/VSCode拡張の
    対話操作を要する項目**(Webログイン、VSCodeログイン、記事公開、画像生成、監査ログの
    目視確認)。サービス間通信・旧ログイン経路の不在・API認可の強制は自動検証済み。
  - **[1.5 管理者トークンの事前取得](#15-管理者トークンの事前取得)の新手順そのもの(issue #766)。**
    以前ここに記載していた `admin-cli` の Resource Owner Password Credentials Grant は、
    同クライアントに `client.use.lightweight.access.token.enabled: "true"` が設定されており
    発行されるトークンに `sub` と `realm_access.roles` が載らないため、
    `requireAdmin()` を通過できないことが判明した(issue #766)。そこで
    `letsblog-web` クライアントの Authorization Code + PKCE フローへ差し替えたが、
    **以下は机上の設計であり実機未検証**である。
    - 取得したトークンで `POST /api/users/migrate-to-keycloak` が403にならないこと
      (letsblog realm の人間管理者のパスワードが必要なため、ユーザー立ち会いのもとで実施する)。
    - NextAuth のコールバック(`/api/auth/callback/keycloak`)が `state` 検証で失敗した際に、
      認可コードを消費せずに残すこと(消費されていると手順3のトークン交換が
      `invalid_grant` で失敗する。その場合は手順2からやり直せばよいが、
      `account` クライアントへの切り替えを含む手順見直しが必要になる)。
    - `refresh_token` グラント([3.1](#31-管理者トークンのリフレッシュ))が、
      reverse-proxy 停止後の `docker exec lbs-gateway curl` 経由で成立すること。
  - 本番相当のデータ量での所要時間([9. 実行記録](#9-実行記録)の実測値は開発環境の
    小さいデータ量に基づく参考値)。
- 上記の残存項目もライブの `docker compose` 環境に対する破壊的操作、または実運用ユーザー
  本人の認証情報を要するため、引き続きユーザー立ち会いの下での実施が必要。
- `docs/BACKUP_RECOVERY_STRATEGY.md` / `docs/BACKUP_RECOVERY_OPERATIONS.md` は
  マイクロサービス分割前の単一スキーマ構成を前提にした記述のままであり、本ドキュメントとの
  対象範囲の食い違いがある(1.2節参照)。両ドキュメントを現行アーキテクチャに合わせて
  更新することは本Issueのスコープ外のため、別Issueとして起票することを推奨する。
- WordPressサイトの動的DBは、命名規則の一覧をコード上で確認できなかった
  (`wordpress/provision-agent/index.php`が`CREATE DATABASE`する際の`dbName`は
  Web/API側から渡されるパラメータであり、固定の命名規則の定義箇所を本調査では特定していない)。
  `mysqldump --all-databases`を使うことで名前に依存せず取りこぼしを防いでいるが、
  復元時の突合(1.4節)は件数ベースの簡易チェックにとどまる。
