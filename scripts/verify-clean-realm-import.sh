#!/bin/bash
# issue #1278 の受入基準検証用スクリプト。
#
# 共有スタック(lbs-*)とは別のcomposeプロジェクトで、空の`keycloak_postgres`から
# `--import-realm`(infra/keycloak/realm-export.json)で起動したKeycloakが、
# クラッシュループせず`healthy`になり、realm `letsblog`を持つことを確認する。
#
# `docker-compose.yml`のkeycloak/keycloak-postgresはcontainer_name: lbs-keycloak /
# lbs-keycloak-postgresを固定しているため、そのまま別プロジェクト名で起動すると
# 稼働中の共有スタックと名前が衝突する。そのため専用のcompose定義
# (scripts/keycloak-clean-boot/docker-compose.yml。container_name無し、
# ホストポート非公開)を `-p <一時プロジェクト名>` で独立に起動する。
#
# 共有スタック(lbs-*)のコンテナ・ボリュームには一切触れない。成功・失敗を問わず、
# 常に一時プロジェクトを削除して終了する(trapでdown -v)。
#
# 使い方:
#   ./scripts/verify-clean-realm-import.sh [--timeout <秒>]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$SCRIPT_DIR/keycloak-clean-boot"
COMPOSE_FILE="$COMPOSE_DIR/docker-compose.yml"
PROJECT_NAME="kc-clean-boot-verify-$$"
TIMEOUT_SECONDS=180
MAX_RESTARTS_BEFORE_FAIL=3

while [ $# -gt 0 ]; do
  case "$1" in
    --timeout)
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    *)
      echo "エラー: 不明な引数 '$1'" >&2
      exit 1
      ;;
  esac
done

compose() {
  docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" --project-directory "$COMPOSE_DIR" "$@"
}

cleanup() {
  echo "--- 後片付け: 一時プロジェクト '$PROJECT_NAME' を削除します(lbs-*には触れません) ---"
  compose down -v --remove-orphans || true
}
trap cleanup EXIT

echo "--- 1/3: 一時プロジェクト '$PROJECT_NAME' で空の状態から起動します ---"
compose up -d

echo "--- 2/3: healthyになり、かつrealm 'letsblog' が応答するまで待機します(最大 ${TIMEOUT_SECONDS}秒) ---"
KEYCLOAK_CID="$(compose ps -q keycloak)"
if [ -z "$KEYCLOAK_CID" ]; then
  echo "NG: keycloakコンテナが見つかりません。" >&2
  exit 1
fi

# `docker inspect`のHealth.Statusだけでは判定しない。Dockerは1回の成功で
# "healthy"に遷移し、その後は`retries`回連続失敗するまで"healthy"のまま留まるため、
# クラッシュループ中でも(再起動直後にKeycloakが一瞬だけ起動する隙間で)
# 一度だけ"healthy"を報告することがある。そのため実際にrealmが読めるかを
# 繰り返し確認し、RestartCountの増加でクラッシュループを直接検出する。
realm_check() {
  compose exec -T keycloak bash -c \
    'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /auth/realms/letsblog HTTP/1.1\r\nhost: localhost\r\nConnection: close\r\n\r\n" >&3 && cat <&3' \
    2>/dev/null || true
}

DEADLINE=$((SECONDS + TIMEOUT_SECONDS))
STATUS="unknown"
REALM_CHECK_OUTPUT=""
IMPORT_OK=0
while [ "$SECONDS" -lt "$DEADLINE" ]; do
  STATUS="$(docker inspect --format='{{.State.Health.Status}}' "$KEYCLOAK_CID" 2>/dev/null || echo "unknown")"
  RESTART_COUNT="$(docker inspect --format='{{.RestartCount}}' "$KEYCLOAK_CID" 2>/dev/null || echo "0")"
  REALM_CHECK_OUTPUT="$(realm_check)"
  if echo "$REALM_CHECK_OUTPUT" | grep -q '"realm":"letsblog"'; then
    echo "  health=$STATUS restarts=$RESTART_COUNT realm=OK (経過 ${SECONDS}秒)"
    IMPORT_OK=1
    break
  fi
  echo "  health=$STATUS restarts=$RESTART_COUNT realm=未確認 (経過 ${SECONDS}秒)"
  if [ "$RESTART_COUNT" -ge "$MAX_RESTARTS_BEFORE_FAIL" ]; then
    echo "NG: クラッシュループを検出しました(RestartCount=$RESTART_COUNT)。直近のログ:" >&2
    docker logs "$KEYCLOAK_CID" --tail 50 >&2 || true
    exit 1
  fi
  sleep 3
done

if [ "$IMPORT_OK" -ne 1 ]; then
  echo "NG: ${TIMEOUT_SECONDS}秒以内にrealm 'letsblog' が確認できませんでした" \
    "(最終health状態: $STATUS)。応答:" >&2
  echo "$REALM_CHECK_OUTPUT" >&2
  echo "直近のログ:" >&2
  docker logs "$KEYCLOAK_CID" --tail 50 >&2 || true
  exit 1
fi

echo "--- 3/3: 起動が安定していることを再確認します(クラッシュループの見逃し防止) ---"
sleep 10
STABLE_RESTART_COUNT="$(docker inspect --format='{{.RestartCount}}' "$KEYCLOAK_CID" 2>/dev/null || echo "0")"
STABLE_STATUS="$(docker inspect --format='{{.State.Health.Status}}' "$KEYCLOAK_CID" 2>/dev/null || echo "unknown")"
if [ "$STABLE_RESTART_COUNT" != "$RESTART_COUNT" ] || [ "$STABLE_STATUS" != "healthy" ]; then
  echo "NG: realm応答後にクラッシュループを検出しました" \
    "(restarts $RESTART_COUNT -> $STABLE_RESTART_COUNT, health=$STABLE_STATUS)。直近のログ:" >&2
  docker logs "$KEYCLOAK_CID" --tail 50 >&2 || true
  exit 1
fi

echo "OK: healthyになり、realm 'letsblog' が存在します(restarts=$STABLE_RESTART_COUNT)。"
exit 0
