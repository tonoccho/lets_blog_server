#!/bin/bash
# docker composeスタックの各サービスがhealthy(ヘルスチェック未定義のものはrunning)になるまで
# 待機する非破壊スクリプト(issue #588)。
#
# E2E(web/e2e)はKeycloak・gateway・各ドメインサービス・reverse-proxyが全て起動している
# 前提で実行される(web/playwright.config.tsのbaseURLはhttps://localhost)。サービス数が
# 増えた結果、`docker compose up -d`の直後は一部サービスがまだ起動途中であることが常態化し、
# テスト開始時刻によって結果が変わる状態になっていた。テスト実行前にこのスクリプトで
# 全サービスのhealthyを待ってから開始する(web/e2e/global-setup.tsから呼ばれる)。
#
# 既定の待機対象はE2Eに必要なサービス群(REQUIRED_SERVICES)のみ。penpot/comfyui/drawio等の
# 起動していない可能性があるオプションサービスは待たない(起動していれば無視される)。
# --all を付けるとcomposeプロジェクト内の全コンテナを対象にする
# (scripts/verify-clean-volume-boot.shはこのモードを使う)。
#
# 使い方:
#   ./scripts/wait-for-stack-healthy.sh [--timeout <秒>] [--services "a b c"] [--all] [--quiet]
#
# 例:
#   ./scripts/wait-for-stack-healthy.sh                       # E2E必須サービスを最大600秒待つ
#   ./scripts/wait-for-stack-healthy.sh --all --timeout 900   # 全コンテナを最大900秒待つ
#   ./scripts/wait-for-stack-healthy.sh --services "gateway keycloak"
#
# 終了コード: 0 = 全対象がhealthy / 1 = タイムアウトまたは対象コンテナ不在
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."

# E2E実行に必要なサービス(docker-compose.yml)。gatewayは各ドメインサービスのhealthyを
# depends_onで待つが、待機対象として明示しておくことで「どれが遅れているか」を可視化する。
REQUIRED_SERVICES="reverse-proxy web gateway keycloak keycloak-postgres mysql rabbitmq \
api identity media ai content analytics project publishing platform log-writer \
legacy-schema-migrate"

TIMEOUT_SECONDS=600
TARGET_SERVICES="$REQUIRED_SERVICES"
WAIT_ALL=0
QUIET=0

while [ $# -gt 0 ]; do
  case "$1" in
    --timeout)
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    --services)
      TARGET_SERVICES="$2"
      shift 2
      ;;
    --all)
      WAIT_ALL=1
      shift
      ;;
    --quiet)
      QUIET=1
      shift
      ;;
    *)
      echo "エラー: 不明な引数 '$1'" >&2
      exit 1
      ;;
  esac
done

cd "$REPO_ROOT"

log() {
  if [ "$QUIET" -ne 1 ]; then
    echo "$@"
  fi
}

log "全サービスがhealthyになるまで待機します(最大 ${TIMEOUT_SECONDS}秒)"

START_TIME="$(date +%s)"
ALL_OK=0

while true; do
  NOW="$(date +%s)"
  ELAPSED=$((NOW - START_TIME))

  # `docker compose ps --format json`はcomposeのバージョンによりNDJSON/JSON配列の
  # どちらも返しうるため、両方を受け付ける(下のpython側で吸収する)。
  STATUS_JSON="$(docker compose ps --all --format json 2>/dev/null || true)"
  PENDING="$(WAIT_ALL="$WAIT_ALL" TARGET_SERVICES="$TARGET_SERVICES" \
    python3 -c '
import json
import os
import sys

raw = sys.stdin.read().strip()
containers = []
if raw:
    if raw.lstrip().startswith("["):
        containers = json.loads(raw)
    else:
        for line in raw.splitlines():
            line = line.strip()
            if line:
                containers.append(json.loads(line))

wait_all = os.environ.get("WAIT_ALL") == "1"
targets = os.environ.get("TARGET_SERVICES", "").split()

by_service = {c.get("Service", "?"): c for c in containers}
if wait_all:
    targets = sorted(by_service)

pending = []
for service in targets:
    c = by_service.get(service)
    if c is None:
        # 対象サービスのコンテナがまだ作成されていない(または起動していない)。
        pending.append(f"{service} (存在しない)")
        continue

    state = c.get("State", "")
    health = c.get("Health", "")
    exit_code = c.get("ExitCode", 0)

    # legacy-schema-migrateは一回限りのFlywayジョブなので、正常終了(exited, code 0)がゴール。
    if service == "legacy-schema-migrate":
        if not (state == "exited" and exit_code == 0):
            pending.append(f"{service} (state={state}, exitCode={exit_code})")
        continue

    if health:
        if health != "healthy":
            pending.append(f"{service} (health={health})")
    else:
        if state != "running":
            pending.append(f"{service} (state={state})")

print("\n".join(pending))
' <<<"$STATUS_JSON")"

  if [ -z "$PENDING" ]; then
    ALL_OK=1
    break
  fi

  if [ "$ELAPSED" -ge "$TIMEOUT_SECONDS" ]; then
    echo "タイムアウト(${TIMEOUT_SECONDS}秒経過)。以下のサービスがまだhealthy/正常終了していません:" >&2
    echo "$PENDING" | sed 's/^/  - /' >&2
    break
  fi

  log "[$ELAPSED s] 待機中: $(echo "$PENDING" | tr '\n' ',' | sed 's/,$//')"
  sleep 5
done

if [ "$ALL_OK" -eq 1 ]; then
  log "OK: 対象サービスは全てhealthy(legacy-schema-migrateは正常終了)です。"
  exit 0
fi

echo "NG: healthyにならなかったサービスがあります。" \
  "'docker compose logs <service>' で原因を確認してください。" >&2
exit 1
