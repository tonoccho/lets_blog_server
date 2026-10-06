#!/bin/bash
# 稼働中のKeycloakへ、infra/keycloak/realm-export.jsonのbrute force detection設定を
# 反映する(issue #1056)。
#
# ■ なぜこのスクリプトが要るか
# infra/keycloak/realm-export.json は「Keycloakコンテナの初回起動時のインポート」にしか
# 効かない。新規クローンでは有効になるが、既に一度起動して稼働中の環境
# (Keycloak用PostgreSQLに既存データがある環境)では、ファイルを書き換えるだけでは
# 何も変わらない。稼働中の環境へ反映するには、Keycloak Admin REST API
# (PUT /admin/realms/letsblog)で直接設定を書き換える必要がある。
#
# ■ 何をするか(冪等)
# 1. .env の KEYCLOAK_ADMIN_USERNAME / KEYCLOAK_ADMIN_PASSWORD で master realm の
#    管理者トークンを取得する(scripts/provision-e2e-keycloak-users.sh と同じ資格情報)。
# 2. 現在の letsblog realm 表現(RealmRepresentation)全体を取得する。
#    PUT は与えたJSON全体で置き換わるため、対象フィールドだけを部分的に送ることはできない
#    ——他の設定(クライアント定義やUI設定等)を巻き込んで消さないよう、既存の表現を
#    そのまま使い、対象フィールドだけを書き換えてから送り返す。
# 3. bruteForceProtected / failureFactor / waitIncrementSeconds / maxFailureWaitSeconds /
#    minimumQuickLoginWaitSeconds / quickLoginCheckMilliSeconds / maxDeltaTimeSeconds /
#    permanentLockout / maxTemporaryLockouts / bruteForceStrategy /
#    maxSecondaryAuthFailures を infra/keycloak/realm-export.json の値に合わせて更新する
#    (このスクリプト自身に値をハードコードせず、realm-export.jsonを正として読む。
#    値がずれて2箇所を個別に直す事故を防ぐため)。
#
# ■ editUsernameAllowed も同じ経路で反映する(#1592)
# メールアドレス更新は #1192 の方針で username も Keycloak へ PUT する。realm の
# editUsernameAllowed が false だと error-user-attribute-read-only で拒否され 502 になるため、
# realm-export.json の値(true)をこのスクリプトで稼働中レルムへ反映する。
# 反映は稼働中 Keycloak の変更であり、実行前に利用者の明示的な確認を得ること
# (CLAUDE.md → Autonomous Task Execution の live-system mutation)。確認なしに実行しない。
# 代替は scripts/rebuild-acceptance-env.sh --yes(ゼロ構築。realm-export.json から再インポート)。
# 反映済みなら PUT せず「変更なし」で終わる(冪等)。
#
# ■ 使い方
#   ./scripts/apply-keycloak-bruteforce-protection.sh
#
# 対象は常にこのdocker composeスタックのKeycloak(https://localhost 経由。
# scripts/provision-e2e-keycloak-users.sh と同じ安全上の制約)。任意のKeycloak URLを
# 指定するオプションは意図的に用意していない。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
ENV_FILE="$REPO_ROOT/.env"
REALM_EXPORT_FILE="$REPO_ROOT/infra/keycloak/realm-export.json"

REALM="letsblog"
API_BASE_URL="https://localhost"

for cmd in curl jq; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "エラー: $cmd が見つかりません(このスクリプトの実行に必要です)" >&2
    exit 1
  fi
done

if [ ! -f "$ENV_FILE" ]; then
  echo "エラー: $ENV_FILE が見つかりません(cp .env.example .env で作成してください)" >&2
  exit 1
fi

if [ ! -f "$REALM_EXPORT_FILE" ]; then
  echo "エラー: $REALM_EXPORT_FILE が見つかりません" >&2
  exit 1
fi

# shellcheck disable=SC1090
KEYCLOAK_ADMIN_USERNAME="$(grep -m1 '^KEYCLOAK_ADMIN_USERNAME=' "$ENV_FILE" | cut -d= -f2-)"
KEYCLOAK_ADMIN_PASSWORD="$(grep -m1 '^KEYCLOAK_ADMIN_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"

if [ -z "${KEYCLOAK_ADMIN_USERNAME:-}" ] || [ -z "${KEYCLOAK_ADMIN_PASSWORD:-}" ]; then
  echo "エラー: .env の KEYCLOAK_ADMIN_USERNAME / KEYCLOAK_ADMIN_PASSWORD が未設定です" >&2
  exit 1
fi

echo "master realm の管理者トークンを取得します..."
ADMIN_TOKEN="$(
  curl -sk -X POST "$API_BASE_URL/auth/realms/master/protocol/openid-connect/token" \
    -d grant_type=password \
    -d client_id=admin-cli \
    -d "username=$KEYCLOAK_ADMIN_USERNAME" \
    -d "password=$KEYCLOAK_ADMIN_PASSWORD" \
    | jq -r '.access_token // empty'
)"

if [ -z "$ADMIN_TOKEN" ]; then
  echo "エラー: 管理者トークンの取得に失敗しました($API_BASE_URL に到達できるか、資格情報を確認してください)" >&2
  exit 1
fi

echo "realm-export.json から目標値を読み取ります..."
DESIRED_JSON="$(
  jq '{
    bruteForceProtected,
    permanentLockout,
    maxTemporaryLockouts,
    bruteForceStrategy,
    maxFailureWaitSeconds,
    minimumQuickLoginWaitSeconds,
    waitIncrementSeconds,
    quickLoginCheckMilliSeconds,
    maxDeltaTimeSeconds,
    failureFactor,
    maxSecondaryAuthFailures,
    editUsernameAllowed
  }' "$REALM_EXPORT_FILE"
)"

echo "現在の realm 設定を取得します..."
CURRENT_REALM_JSON="$(
  curl -sk "$API_BASE_URL/auth/admin/realms/$REALM" -H "Authorization: Bearer $ADMIN_TOKEN"
)"

if [ -z "$CURRENT_REALM_JSON" ] || ! echo "$CURRENT_REALM_JSON" | jq -e '.realm' >/dev/null 2>&1; then
  echo "エラー: realm '$REALM' の取得に失敗しました: $CURRENT_REALM_JSON" >&2
  exit 1
fi

UPDATED_REALM_JSON="$(
  echo "$CURRENT_REALM_JSON" | jq --argjson desired "$DESIRED_JSON" '. * $desired'
)"

if [ "$(echo "$UPDATED_REALM_JSON" | jq -S .)" = "$(echo "$CURRENT_REALM_JSON" | jq -S .)" ]; then
  echo "既に realm-export.json と一致しています(変更なし)。"
  exit 0
fi

echo "brute force detection / editUsernameAllowed の設定を反映します (PUT /admin/realms/$REALM)..."
HTTP_STATUS="$(
  curl -sk -o /tmp/apply-keycloak-bruteforce-protection.response.json -w '%{http_code}' \
    -X PUT "$API_BASE_URL/auth/admin/realms/$REALM" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "$UPDATED_REALM_JSON"
)"

if [ "$HTTP_STATUS" != "204" ]; then
  echo "エラー: 反映に失敗しました (HTTP $HTTP_STATUS)" >&2
  cat /tmp/apply-keycloak-bruteforce-protection.response.json >&2 || true
  exit 1
fi

echo "反映しました。現在の設定を確認します:"
curl -sk "$API_BASE_URL/auth/admin/realms/$REALM" -H "Authorization: Bearer $ADMIN_TOKEN" \
  | jq '{
    bruteForceProtected,
    failureFactor,
    waitIncrementSeconds,
    maxFailureWaitSeconds,
    permanentLockout,
    editUsernameAllowed
  }'
