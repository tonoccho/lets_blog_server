#!/bin/bash
# E2E(web/e2e)専用の合成アカウントをローカル開発環境へプロビジョニングする(issue #588)。
#
#   e2e-test@letsblog.local   role=user  (非admin側の検証用)
#   e2e-admin@letsblog.local  role=admin (admin側の検証用。realmロール admin を付与)
#
# 操作対象は上記2件の e2e-*@letsblog.local に限定され、それ以外のアカウントは
# 作成・変更・削除しない。実ユーザー(s.tonouchi@gmail.com等)は、issue #796 以降
# identity-service を admin として呼ぶための**認証にだけ**使い(手順0)、
# 作成・変更・削除の対象にはしない。
#
# ■ 安全上の制約(重要)
# このスクリプトは「ローカル開発用のdocker composeで起動しているKeycloakコンテナ
# (コンテナ名 lbs-keycloak)」に対してのみ動作する。任意のKeycloak URLを指定する
# オプションは意図的に用意していない(共有/本番Keycloakへ誤って実行できないようにするため)。
# 共有環境では実行せず、docs/e2e-testing.mdの手順に従って手動で発行すること。
#
# ■ 何をするか
# 0. E2E専用のKeycloakクライアント letsblog-e2e を用意し、そのpasswordグラントで
#    **実在するadminユーザー**のアクセストークンを取得する。issue #772 で認証ゲート
#    (有効なJWTが無ければ401)が、issue #796 で POST /api/users のadmin必須が入ったため、
#    ユーザー作成には管理者のトークンが要る。資格情報は環境変数
#    E2E_PROVISION_ADMIN_EMAIL / E2E_PROVISION_ADMIN_PASSWORD で渡す(.envには保存しない)。
# 1. identity-service(gateway経由 https://localhost/api/users)へユーザー作成を要求する。
#    identity-serviceはKeycloak側のユーザー作成とローカルDB(lets_blog.users、keycloak_sub付き)
#    への登録を1トランザクションで行う。両方揃っていないとE2Eのadmin操作は通らない
#    (CurrentActorServiceがJWTのsubからローカルUserを引くため)。
# 2. Keycloak Admin CLI(コンテナ内のkcadm.sh)でパスワードを設定する(temporary=false)。
#    identity-service経由の作成ではKeycloakの資格情報までは設定されないため、この手順が必要。
# 3. adminアカウントにrealmロール admin を付与する(JWTのrealm_access.rolesに載る)。
# 4. (手順0で作成済み)E2E専用のKeycloakクライアント letsblog-e2e(issue #588)。
#    web/e2eがブラウザを介さずAPIを直接叩く際のトークン発行に使う。realm既定のadmin-cliは
#    lightweight access tokenが有効でsub/realm_access.rolesが載らず、下流サービスの認可が
#    通らないため、E2E専用クライアントを別に用意する。keycloak/realm-export.jsonにも
#    同じ定義があるが、Keycloakはexportを初回起動時にしか読まないため既存環境向けにここでも作る。
#
# 既に存在するアカウント/クライアントに対しては作成をスキップし、
# パスワード再設定・ロール付与・設定の整合だけを行う(冪等)。
#
# ■ 使い方
#   E2E_PROVISION_ADMIN_EMAIL='<letsblog realmの管理者>' \
#   E2E_PROVISION_ADMIN_PASSWORD='...' \
#   E2E_TEST_PASSWORD='...' E2E_ADMIN_PASSWORD='...' ./scripts/provision-e2e-keycloak-users.sh
#
# .env の KEYCLOAK_ADMIN_USERNAME / KEYCLOAK_ADMIN_PASSWORD をKeycloak管理者資格情報として使う。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
ENV_FILE="$REPO_ROOT/.env"

KEYCLOAK_CONTAINER="lbs-keycloak"
REALM="letsblog"
# reverse-proxy(nginx)経由の公開URL。gateway → identity-service へルーティングされる。
API_BASE_URL="https://localhost"

TEST_EMAIL="e2e-test@letsblog.local"
ADMIN_EMAIL="e2e-admin@letsblog.local"
# web/e2e の fetchAccessToken() が使うクライアント(helpers.ts と一致させること)。
E2E_CLIENT_ID="letsblog-e2e"

if [ ! -f "$ENV_FILE" ]; then
  echo "エラー: $ENV_FILE が見つかりません(cp .env.example .env で作成してください)" >&2
  exit 1
fi

# shellcheck disable=SC1090
KEYCLOAK_ADMIN_USERNAME="$(grep -m1 '^KEYCLOAK_ADMIN_USERNAME=' "$ENV_FILE" | cut -d= -f2-)"
KEYCLOAK_ADMIN_PASSWORD="$(grep -m1 '^KEYCLOAK_ADMIN_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"

if [ -z "${KEYCLOAK_ADMIN_USERNAME:-}" ] || [ -z "${KEYCLOAK_ADMIN_PASSWORD:-}" ]; then
  echo "エラー: .env の KEYCLOAK_ADMIN_USERNAME / KEYCLOAK_ADMIN_PASSWORD が未設定です" >&2
  exit 1
fi

if [ -z "${E2E_PROVISION_ADMIN_EMAIL:-}" ] || [ -z "${E2E_PROVISION_ADMIN_PASSWORD:-}" ]; then
  echo "エラー: 環境変数 E2E_PROVISION_ADMIN_EMAIL と E2E_PROVISION_ADMIN_PASSWORD を指定してください" >&2
  echo "  identity-serviceの POST /api/users はadmin限定(issue #796)のため、letsblog realm に" >&2
  echo "  実在する管理者アカウントの資格情報が必要です。" >&2
  echo "  例: E2E_PROVISION_ADMIN_EMAIL='admin@example.com' E2E_PROVISION_ADMIN_PASSWORD='...' \\" >&2
  echo "      E2E_TEST_PASSWORD='...' E2E_ADMIN_PASSWORD='...' $0" >&2
  exit 1
fi

if [ -z "${E2E_TEST_PASSWORD:-}" ] || [ -z "${E2E_ADMIN_PASSWORD:-}" ]; then
  echo "エラー: 環境変数 E2E_TEST_PASSWORD と E2E_ADMIN_PASSWORD を指定してください" >&2
  echo "  例: E2E_TEST_PASSWORD='...' E2E_ADMIN_PASSWORD='...' $0" >&2
  exit 1
fi

if ! docker inspect "$KEYCLOAK_CONTAINER" >/dev/null 2>&1; then
  echo "エラー: コンテナ ${KEYCLOAK_CONTAINER} が見つかりません(docker compose up -d keycloak で起動してください)" >&2
  exit 1
fi

kcadm() {
  docker exec "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"
}

# kcadm の `-f -`(標準入力からJSONを読む)を使う呼び出し用。docker exec に -i が必要。
kcadm_stdin() {
  docker exec -i "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"
}

echo "--- Keycloak管理CLIへログインします(コンテナ内: ${KEYCLOAK_CONTAINER}) ---"
kcadm config credentials \
  --server http://localhost:8080/auth \
  --realm master \
  --user "$KEYCLOAK_ADMIN_USERNAME" \
  --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

# identity-serviceの POST /api/users を呼ぶためのアクセストークンを取得する。
#
# issue #772 では letsblog-services の Client Credentials を使っていた。当時
# POST /api/users は認可チェックを持たず、認証ゲートさえ通れば作成できたためである。
# issue #796 で requireAdmin() を追加したので、この方式はもう通らない:
# サービスアカウントのsubに対応するローカルusers行が無く、CurrentActorServiceが
# 操作者を解決できないため403になる。
#
# ユーザー作成は本来admin操作なので、**実在のadminユーザーのトークン**を使う。
# letsblog-e2e クライアント(public / direct access grant 有効 / lightweight token 無効)の
# password グラントで取得する。lightweight でないため sub が載り、requireAdmin() が通る。
#
# 前提: letsblog realm に admin ロールのユーザーが既に存在すること。ローカル開発環境では
# 初回セットアップ(legacy-api の /api/auth/setup)で作られた管理者アカウントが該当する。
ADMIN_ACCESS_TOKEN=""
fetch_admin_access_token() {
  local response
  response="$(curl -sk -X POST \
    "${API_BASE_URL}/auth/realms/${REALM}/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d 'grant_type=password' \
    -d "client_id=${E2E_CLIENT_ID}" \
    --data-urlencode "username=${E2E_PROVISION_ADMIN_EMAIL}" \
    --data-urlencode "password=${E2E_PROVISION_ADMIN_PASSWORD}")"

  ADMIN_ACCESS_TOKEN="$(printf '%s' "$response" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"

  if [ -z "$ADMIN_ACCESS_TOKEN" ]; then
    echo "エラー: 管理者アクセストークンの取得に失敗しました" >&2
    echo "  E2E_PROVISION_ADMIN_EMAIL / E2E_PROVISION_ADMIN_PASSWORD が letsblog realm の" >&2
    echo "  実在する管理者アカウントと一致しているか、Keycloakとreverse-proxyが起動しているかを" >&2
    echo "  確認してください。応答: ${response}" >&2
    exit 1
  fi

  # requireAdmin() を通れるトークンかを、admin限定のGET /api/usersで先に確かめる。
  # ここで弾いておかないと、後続のユーザー作成が403で落ちた理由が分かりにくい。
  local status
  status="$(curl -sk -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer ${ADMIN_ACCESS_TOKEN}" "${API_BASE_URL}/api/users")"
  if [ "$status" != "200" ]; then
    echo "エラー: 取得したトークンでは管理者操作ができません(GET /api/users が HTTP ${status})" >&2
    echo "  ${E2E_PROVISION_ADMIN_EMAIL} が letsblog realm と lets_blog.users の両方で" >&2
    echo "  admin ロールになっているか確認してください(issue #796 で /api/users の書き込み系にも" >&2
    echo "  admin 権限が必要になりました)。" >&2
    exit 1
  fi
}

# $1: email, $2: password, $3: role(user|admin)
provision_user() {
  local email="$1"
  local password="$2"
  local role="$3"

  # 安全弁: このスクリプトはE2E専用の合成アカウント以外を絶対に操作しない。
  case "$email" in
    e2e-*@letsblog.local) ;;
    *)
      echo "エラー: ${email} はE2E専用アカウント(e2e-*@letsblog.local)ではありません。中止します。" >&2
      exit 1
      ;;
  esac

  echo "--- ${email} (role=${role}) をプロビジョニングします ---"

  local status
  status="$(curl -sk -o /dev/null -w '%{http_code}' \
    -X POST "${API_BASE_URL}/api/users" \
    -H "Authorization: Bearer ${ADMIN_ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${email}\",\"password\":\"${password}\",\"role\":\"${role}\"}")"

  case "$status" in
    201)
      echo "  identity-service: 新規作成しました(Keycloak + ローカルDB)"
      ;;
    401)
      echo "エラー: identity-serviceが401を返しました(issue #772で認証ゲートを復元済み)" >&2
      echo "  アクセストークンがidentity-serviceで検証できていません(期限切れの可能性)。" >&2
      exit 1
      ;;
    403)
      echo "エラー: identity-serviceが403を返しました(issue #796でPOST /api/usersはadmin限定)" >&2
      echo "  ${E2E_PROVISION_ADMIN_EMAIL} がadminロールを持っているか確認してください。" >&2
      exit 1
      ;;
    400|409)
      echo "  identity-service: 既に存在するためスキップします(HTTP ${status})"
      echo "  注意: 既存アカウントのローカルDB上のroleはこのスクリプトでは変更しません。" \
        "role=${role}になっていない場合は/usersの管理画面から変更してください。"
      ;;
    *)
      echo "エラー: identity-serviceへのユーザー作成要求が失敗しました(HTTP ${status})" >&2
      echo "  gateway/identity/keycloakが起動しているか確認してください" \
        "(./scripts/wait-for-stack-healthy.sh)。" >&2
      exit 1
      ;;
  esac

  local user_id
  user_id="$(kcadm get users -r "$REALM" -q "email=${email}" --fields id --format csv --noquotes \
    | tr -d '\r' | head -n1)"
  if [ -z "$user_id" ]; then
    echo "エラー: Keycloak上に ${email} が見つかりません" >&2
    exit 1
  fi

  kcadm set-password -r "$REALM" --userid "$user_id" --new-password "$password" --temporary=false
  echo "  Keycloak: パスワードを設定しました(temporary=false)"

  if [ "$role" = "admin" ]; then
    kcadm add-roles -r "$REALM" --uid "$user_id" --rolename admin
    echo "  Keycloak: realmロール admin を付与しました"
  fi
}

# E2E専用クライアントを作成/更新する。既存のadmin-cli等の実運用クライアントには一切触れない。
provision_e2e_client() {
  echo "--- Keycloakクライアント ${E2E_CLIENT_ID} をプロビジョニングします ---"

  # publicClient + directAccessGrants のみ。standardFlowは無効(リダイレクト先を持たない)。
  # lightweight access token は有効にしない(subとrealm_access.rolesを落としてしまうため)。
  local payload
  payload="$(cat <<JSON
{
  "clientId": "${E2E_CLIENT_ID}",
  "name": "Let's Blog E2E (local development only)",
  "enabled": true,
  "protocol": "openid-connect",
  "publicClient": true,
  "standardFlowEnabled": false,
  "implicitFlowEnabled": false,
  "directAccessGrantsEnabled": true,
  "serviceAccountsEnabled": false,
  "fullScopeAllowed": true,
  "redirectUris": [],
  "webOrigins": [],
  "attributes": { "realm_client": "false" }
}
JSON
)"

  local client_uuid
  client_uuid="$(kcadm get clients -r "$REALM" -q "clientId=${E2E_CLIENT_ID}" \
    --fields id --format csv --noquotes | tr -d '\r' | head -n1)"

  if [ -z "$client_uuid" ]; then
    printf '%s' "$payload" | kcadm_stdin create clients -r "$REALM" -f - >/dev/null
    echo "  作成しました(public / direct access grant 可 / lightweight access token 無効)"
  else
    printf '%s' "$payload" | kcadm_stdin update "clients/${client_uuid}" -r "$REALM" -f - >/dev/null
    echo "  既に存在するため設定を上書きしました(冪等)"
  fi
}

# letsblog-e2e クライアントを先に用意する。管理者トークンの取得(password グラント)に
# このクライアントを使うため、ユーザー作成より前に存在している必要がある(issue #796)。
provision_e2e_client

echo "--- identity-service呼び出し用の管理者トークンを取得します ---"
fetch_admin_access_token

provision_user "$TEST_EMAIL" "$E2E_TEST_PASSWORD" "user"
provision_user "$ADMIN_EMAIL" "$E2E_ADMIN_PASSWORD" "admin"

echo ""
echo "完了しました。E2E実行時は同じ値を環境変数で渡してください:"
echo "  E2E_TEST_PASSWORD / E2E_ADMIN_PASSWORD"
