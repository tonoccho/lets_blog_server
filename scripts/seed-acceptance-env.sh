#!/bin/bash
# リセット直後の受け入れテスト環境に、全シナリオが前提とする最小の状態を作る
# (issue #945 / AT-19)。
#
# ■ 何を作るか
#
#   1. 最初の管理者(初回セットアップ POST /api/auth/setup)
#      ただし**通常はこの手順を実行しない**。初回セットアップの検証シナリオ(AT-3 / #929、
#      タグ `@stage:setup`)が段階実行の最初に走り、その実行そのものが最初の管理者を作るためである。
#      同じことを2か所で定義しないよう、ここは「ユーザーが0人のまま来た場合の補完」に留める
#      (単一ドメインのテストだけを回したいときなど、setup 段階を通さない実行のため)。
#
#   2. E2E専用の合成アカウント(e2e-test / e2e-admin)
#      scripts/provision-e2e-keycloak-users.sh に委譲する。
#
#   3. WordPress のプロビジョニング
#      **ここでは行わない**。利用者の指示により、プロビジョニングは受け入れテストの
#      一段階(AT-5 / #931、タグ `@stage:provision`)として実行し、成功してから他へ進む。
#      シードで先に作ってしまうと、その検証が「既にあるものを確認するだけ」に退化する。
#
# ■ 資格情報
#
#   ~/.config/lets-blog-e2e.env(モード600、リポジトリ外)から次を export しておくこと。
#     E2E_TEST_PASSWORD / E2E_ADMIN_PASSWORD
#     E2E_PROVISION_ADMIN_EMAIL / E2E_PROVISION_ADMIN_PASSWORD
#
# ■ 使い方
#
#   source ~/.config/lets-blog-e2e.env
#   ./scripts/seed-acceptance-env.sh
#
# 冪等。2回続けて実行しても同じ結果になる。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."

readonly BASE_URL="https://localhost"
readonly KEYCLOAK_CONTAINER="lbs-keycloak"
readonly KEYCLOAK_REALM="letsblog"
readonly SETUP_ADMIN_EMAIL="${E2E_PROVISION_ADMIN_EMAIL:-}"
readonly SETUP_ADMIN_PASSWORD="${E2E_PROVISION_ADMIN_PASSWORD:-}"

log()  { printf '%s\n' "$*"; }
step() { printf '\n--- %s ---\n' "$*"; }

for var in E2E_TEST_PASSWORD E2E_ADMIN_PASSWORD E2E_PROVISION_ADMIN_EMAIL E2E_PROVISION_ADMIN_PASSWORD; do
  if [ -z "${!var:-}" ]; then
    echo "エラー: $var が未設定です。~/.config/lets-blog-e2e.env を source してください" >&2
    exit 1
  fi
done

START_TS=$(date +%s)

step "1/3 初回セットアップの状態を確認します"
SETUP_STATUS="$(curl -sk -m 10 "$BASE_URL/api/auth/setup-status" || true)"
log "  GET /api/auth/setup-status → ${SETUP_STATUS:-(応答なし)}"

# AuthSetupController#setupStatus は {"needsSetup": <bool>} だけを返す。
NEEDS_SETUP="$(printf '%s' "$SETUP_STATUS" | python3 -c "
import sys, json
try:
    print('yes' if json.load(sys.stdin)['needsSetup'] else 'no')
except Exception:
    print('unknown')
" 2>/dev/null || echo unknown)"

case "$NEEDS_SETUP" in
  no)
    log "  管理者は既に存在します(AT-3 の setup 段階が作成済み、または前回のシード)。手順1はスキップします。"
    ;;
  yes)
    log "  ユーザーが0人です。補完として最初の管理者を作成します。"
    log "  (本来は AT-3 の @stage:setup シナリオがこれを行います)"
    CODE="$(curl -sk -m 30 -o /tmp/at-seed-setup.json -w '%{http_code}' \
      -XPOST "$BASE_URL/api/auth/setup" -H 'Content-Type: application/json' \
      -d "$(python3 -c "
import json,os
print(json.dumps({
    'email': os.environ['E2E_PROVISION_ADMIN_EMAIL'],
    'password': os.environ['E2E_PROVISION_ADMIN_PASSWORD'],
}))")" || echo 000)"
    if [ "$CODE" = "200" ] || [ "$CODE" = "201" ]; then
      log "  OK: 最初の管理者を作成しました ($SETUP_ADMIN_EMAIL)"
    else
      echo "エラー: 初回セットアップに失敗しました (status=$CODE)" >&2
      cat /tmp/at-seed-setup.json >&2 2>/dev/null || true
      exit 1
    fi
    ;;
  *)
    echo "エラー: setup-status の応答を解釈できませんでした: ${SETUP_STATUS:-(応答なし)}" >&2
    echo "       スタックが起動しているか確認してください。" >&2
    exit 1
    ;;
esac

step "2/3 初回セットアップで作った管理者にKeycloakの資格情報を設定します"
# POST /api/auth/setup は identity-service 側のユーザーと Keycloak 側のユーザーを作るが、
# **Keycloakのパスワードまでは設定しない**。そのため作られた直後のアカウントは
# 「Account is not fully set up」でパスワードグラントに失敗し、次の手順3
# (provision-e2e-keycloak-users.sh)が実在する管理者として認証できない。
# リセット直後は他に管理者が居ないため、ここで資格情報を整えるまで先へ進めない。
kcadm() { docker exec "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }

KEYCLOAK_ADMIN_USERNAME="$(grep -m1 '^KEYCLOAK_ADMIN_USERNAME=' "$REPO_ROOT/.env" | cut -d= -f2-)"
KEYCLOAK_ADMIN_PASSWORD="$(grep -m1 '^KEYCLOAK_ADMIN_PASSWORD=' "$REPO_ROOT/.env" | cut -d= -f2-)"
if [ -z "${KEYCLOAK_ADMIN_USERNAME:-}" ] || [ -z "${KEYCLOAK_ADMIN_PASSWORD:-}" ]; then
  echo "エラー: .env の KEYCLOAK_ADMIN_USERNAME / KEYCLOAK_ADMIN_PASSWORD が未設定です" >&2
  exit 1
fi

kcadm config credentials \
  --server http://localhost:8080/auth --realm master \
  --user "$KEYCLOAK_ADMIN_USERNAME" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

SETUP_USER_ID="$(kcadm get users -r "$KEYCLOAK_REALM" -q "email=$SETUP_ADMIN_EMAIL" --fields id 2>/dev/null \
  | python3 -c "
import sys, json
try:
    users = json.load(sys.stdin)
except Exception:
    users = []
print(users[0]['id'] if users else '')
")"

if [ -z "$SETUP_USER_ID" ]; then
  echo "エラー: Keycloak に $SETUP_ADMIN_EMAIL が見つかりません" >&2
  echo "       POST /api/auth/setup が Keycloak 側のユーザーを作れていない可能性があります。" >&2
  exit 1
fi

kcadm set-password -r "$KEYCLOAK_REALM" --userid "$SETUP_USER_ID" \
  --new-password "$SETUP_ADMIN_PASSWORD" >/dev/null

# firstName/lastName を必ず埋めること。
#
# このレルムでは required action の VERIFY_PROFILE が有効になっている。プロフィールが
# 不完全なユーザーは、ブラウザのログインでは「プロフィールを補完してください」の画面が
# 出るだけだが、**パスワードグラント(直接付与)では "Account is not fully set up" で
# 失敗する**。identity-service の KeycloakAdminClient#createUser は firstName/lastName を
# 送らないため、POST /api/auth/setup で作られた最初の管理者は必ずこの状態になる。
# 2026-09-01 に実測: 名前2つを埋めた瞬間にトークンが取れるようになった。
#
# requiredActions / emailVerified / enabled も併せて整える(UPDATE_PASSWORD 等が
# 残っていると同じ理由でグラントが通らない)。
kcadm update "users/$SETUP_USER_ID" -r "$KEYCLOAK_REALM" \
  -s 'requiredActions=[]' -s 'emailVerified=true' -s 'enabled=true' \
  -s 'firstName=E2E' -s 'lastName=Setup Admin' >/dev/null
log "  OK: $SETUP_ADMIN_EMAIL のパスワードとプロフィールを設定しました"

step "3/3 E2E専用の合成アカウントを発行します"
"$REPO_ROOT/scripts/provision-e2e-keycloak-users.sh"

ELAPSED=$(( $(date +%s) - START_TS ))
step "完了"
log "所要時間: ${ELAPSED} 秒"
log "WordPress のプロビジョニングは受け入れテストの @stage:provision 段階(AT-5 / #931)が行います。"
