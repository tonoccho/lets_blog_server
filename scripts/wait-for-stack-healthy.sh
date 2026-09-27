#!/bin/bash
# docker composeスタックの各サービスがhealthy(ヘルスチェック未定義のものはrunning)になるまで
# 待機する非破壊スクリプト(issue #588)。
#
# E2E(apps/web/e2e)はKeycloak・gateway・各ドメインサービス・reverse-proxyが全て起動している
# 前提で実行される(apps/web/playwright.config.tsのbaseURLはhttps://localhost)。サービス数が
# 増えた結果、`docker compose up -d`の直後は一部サービスがまだ起動途中であることが常態化し、
# テスト開始時刻によって結果が変わる状態になっていた。テスト実行前にこのスクリプトで
# 全サービスのhealthyを待ってから開始する(apps/web/e2e/global-setup.tsから呼ばれる)。
#
# 既定の待機対象はE2Eに必要なサービス群(REQUIRED_SERVICES)のみ。penpot/comfyui/drawio等の
# 起動していない可能性があるオプションサービスは待たない(起動していれば無視される)。
# --all を付けるとcomposeプロジェクト内の全コンテナを対象にする
# (scripts/verify-clean-volume-boot.shはこのモードを使う)。
#
# ## コンテナがhealthyでも、ホストから届くとは限らない(issue #1038)
#
# 以前はコンテナの health 状態だけを見ていた。しかしホストの80/443を他プロセス
# (この開発機ではGitLabを提供するinfra-proxy)が占有していると、Dockerはネットワーク接続時の
# ポート公開に失敗し、reverse-proxyは**どのネットワークにも所属しないまま running になる**。
# それでもヘルスチェックは自分のnetns内の127.0.0.1を叩くだけなので healthy を返し、
# このスクリプトは `OK: 対象サービスは全てhealthyです` を返していた(実測)。
# 直後にE2Eのglobal-setupが https://localhost へ到達できずに落ちるのに、である。
#
# そこで、コンテナのhealthyを待ったあとに**ホストからbaseURLへ実際に届くこと**も検査する。
# 待機対象にreverse-proxyが含まれるとき(既定と--all)だけ行う。
#
# 使い方:
#   ./scripts/wait-for-stack-healthy.sh [--timeout <秒>] [--services "a b c"] [--all] [--quiet]
#                                       [--http-timeout <秒>] [--skip-http-check | --http-only]
#
# 例:
#   ./scripts/wait-for-stack-healthy.sh                       # E2E必須サービスを最大600秒待つ
#   ./scripts/wait-for-stack-healthy.sh --all --timeout 900   # 全コンテナを最大900秒待つ
#   ./scripts/wait-for-stack-healthy.sh --services "gateway keycloak"
#   ./scripts/wait-for-stack-healthy.sh --http-only           # ホストからの到達性だけを見る
#
# 環境変数:
#   E2E_BASE_URL         到達性を確認する公開URL(既定 https://localhost)。
#                        apps/web/playwright.config.ts の baseURL と揃えること。
#   E2E_SKIP_HTTP_CHECK  1 なら到達性の検査を省く(--skip-http-check と同じ)
#
# 終了コード: 0 = 全対象がhealthyかつ公開URLへ到達可 / 1 = タイムアウト・対象不在・到達不可
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# composeプロジェクトを明示的に解決する(issue #842)。
#
# 以前は `docker compose ps` を -f も -p も付けずに実行していたため、対象プロジェクトの
# 解決がカレントディレクトリ任せだった。test-e2eコンテナの中(network_mode: host、
# docker.sockマウント)から走らせると解決に失敗し、スタックが実際には全てhealthyでも
# 全サービスを「存在しない」と報告して600秒待ってから落ちていた。
#
# COMPOSE_PROJECT_NAME が設定されていればそれを使い、無ければ compose 自身の既定と同じく
# リポジトリのディレクトリ名を使う。git worktree からはメイン作業ツリーのディレクトリ名
# (共有スタックの名前)を使う(#1201。導出は scripts/lib/compose-project.sh の1箇所)。
# compose ファイルもリポジトリ基準の絶対パスで渡す。
# shellcheck source=lib/compose-project.sh
source "$SCRIPT_DIR/lib/compose-project.sh"
COMPOSE_PROJECT="$(resolve_compose_project "$REPO_ROOT")"
COMPOSE_ARGS=(-p "$COMPOSE_PROJECT" -f "$REPO_ROOT/docker-compose.yml")

# E2E実行に必要なサービス(docker-compose.yml)。gatewayは各ドメインサービスのhealthyを
# depends_onで待つが、待機対象として明示しておくことで「どれが遅れているか」を可視化する。
REQUIRED_SERVICES="reverse-proxy web gateway keycloak keycloak-postgres mysql rabbitmq \
identity media ai content analytics project publishing platform log-writer"

TIMEOUT_SECONDS=600
TARGET_SERVICES="$REQUIRED_SERVICES"

# 正常終了(exited, code 0)がゴールのワンショットジョブ(issue #1439)。
# docker-compose.yml で `restart: "no"` のサービスは全てここに載せること
# (scripts/test_wait_all_one_shot.py が突き合わせて、載っていなければ落とす)。
# 載せ忘れると --all が定常状態の exited を永久に待ち、必ずタイムアウトする。
ONE_SHOT_JOBS="ollama-model-init"
WAIT_ALL=0
QUIET=0
HTTP_ONLY=0
HTTP_TIMEOUT_SECONDS=120
BASE_URL="${E2E_BASE_URL:-https://localhost}"
SKIP_HTTP_CHECK=0
if [ "${E2E_SKIP_HTTP_CHECK:-0}" = "1" ]; then
  SKIP_HTTP_CHECK=1
fi

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
    --http-timeout)
      HTTP_TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    --skip-http-check)
      SKIP_HTTP_CHECK=1
      shift
      ;;
    --http-only)
      HTTP_ONLY=1
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

# ホストから公開URLへ届くかを見る(issue #1038)。
#
# コンテナのhealthyとは別物である。reverse-proxyがネットワークから切り離されていても
# コンテナ内の127.0.0.1は応答するため、ここだけがその状態を捕まえられる。
#
# curlが無ければbusybox wgetで代用する。どちらも無い環境(docker CLIすら無い実行環境を
# 想定した E2E_SKIP_HEALTH_WAIT と同じ事情)では、黙って通すのではなく**省いたことを表示**して
# 通す。検査しなかったことが見えないまま緑になるのが、この Issue が直している壊れ方である。
probe_base_url() {
  if command -v curl >/dev/null 2>&1; then
    curl -sk -o /dev/null -w '%{http_code}' --max-time 10 "${BASE_URL%/}/" 2>/dev/null || true
    return 0
  fi
  if command -v wget >/dev/null 2>&1; then
    if wget -q -O /dev/null --no-check-certificate --timeout=10 "${BASE_URL%/}/" 2>/dev/null; then
      echo "200"
    else
      echo "000"
    fi
    return 0
  fi
  echo ""
}

check_base_url_reachable() {
  if [ "$SKIP_HTTP_CHECK" -eq 1 ]; then
    log "公開URLの到達確認はスキップします(--skip-http-check / E2E_SKIP_HTTP_CHECK=1)"
    return 0
  fi

  log "ホストから ${BASE_URL} へ到達できるか確認します(最大 ${HTTP_TIMEOUT_SECONDS}秒)"
  local start now code=""
  start="$(date +%s)"
  while true; do
    code="$(probe_base_url)"
    if [ -z "$code" ]; then
      echo "△ curl も wget も無いため ${BASE_URL} への到達確認を省きました。" >&2
      return 0
    fi
    case "$code" in
      2??|3??)
        log "OK: ${BASE_URL}/ へ到達できます (http_code=${code})"
        return 0
        ;;
    esac
    now="$(date +%s)"
    if [ $((now - start)) -ge "$HTTP_TIMEOUT_SECONDS" ]; then
      break
    fi
    log "[$((now - start)) s] 待機中: ${BASE_URL}/ が http_code=${code}"
    sleep 5
  done

  echo "NG: ホストから ${BASE_URL}/ へ到達できません (http_code=${code})。" >&2
  echo "  コンテナは healthy でも、ホストへのポート公開が成立していない可能性があります。" >&2
  echo "  ホストの 80/443 を他プロセスが占有していないか確認してください:" >&2
  echo "    ss -ltnp | grep -E ':(80|443)\\s'" >&2
  echo "    docker inspect lbs-reverse-proxy --format '{{json .NetworkSettings.Networks}}'" >&2
  echo "  占有しているプロセスと共存させる手順(#1038):" >&2
  echo "    bash scripts/setup-shared-host-proxy.sh --check" >&2
  echo "    docs/ACCEPTANCE_TESTING.md「ホストの80/443を他プロセスが占有している場合」" >&2
  return 1
}

# --http-only はコンテナの待機を行わず、ホストからの到達性だけを見る。
# スタックが healthy と報告されているのに E2E が落ちるときの切り分けに使う。
if [ "$HTTP_ONLY" -eq 1 ]; then
  check_base_url_reachable
  exit $?
fi

log "全サービスがhealthyになるまで待機します(最大 ${TIMEOUT_SECONDS}秒)"

START_TIME="$(date +%s)"
ALL_OK=0

while true; do
  NOW="$(date +%s)"
  ELAPSED=$((NOW - START_TIME))

  # `docker compose ps --format json`はcomposeのバージョンによりNDJSON/JSON配列の
  # どちらも返しうるため、両方を受け付ける(下のpython側で吸収する)。
  #
  # issue #842: 以前は `2>/dev/null || true` でエラーを握り潰していたため、
  # 「composeを解決できない」と「まだ起動していない」が区別できなかった。
  # stderrを捕まえて、失敗したら理由ごと表示する。
  PS_STDERR="$(mktemp)"
  if ! STATUS_JSON="$(docker compose "${COMPOSE_ARGS[@]}" ps --all --format json 2>"$PS_STDERR")"; then
    echo "エラー: docker compose ps に失敗しました(project=${COMPOSE_PROJECT})。" >&2
    sed 's/^/  /' "$PS_STDERR" >&2
    rm -f "$PS_STDERR"
    echo "  compose ファイル: $REPO_ROOT/docker-compose.yml" >&2
    echo "  docker CLI が使えない環境では E2E_SKIP_HEALTH_WAIT=1 で待機を飛ばせます。" >&2
    exit 1
  fi
  rm -f "$PS_STDERR"
  PENDING="$(WAIT_ALL="$WAIT_ALL" TARGET_SERVICES="$TARGET_SERVICES" ONE_SHOT_JOBS="$ONE_SHOT_JOBS" \
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
ONE_SHOT_JOBS = set(os.environ.get("ONE_SHOT_JOBS", "").split())
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

    # 一回限りのジョブ(ワンショットコンテナ)は正常終了(exited, code 0)がゴール。
    # 対象は bash 側の ONE_SHOT_JOBS(現在は ollama-model-init。issue #1439)。
    if service in ONE_SHOT_JOBS:
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

  # 対象が1つも見つからない場合、待っても状況は変わらない(プロジェクトの解決ミスか、
  # スタックがそもそも起動していないかのどちらか)。600秒待ってから同じメッセージを出すと
  # 設定ミスなのか起動が遅いだけなのか読み取れないため、初回の観測で即座に落とす(issue #842)。
  MISSING_COUNT="$(echo "$PENDING" | grep -c '(存在しない)' || true)"
  TOTAL_COUNT="$(echo "$PENDING" | grep -c . || true)"
  if [ "$MISSING_COUNT" -eq "$TOTAL_COUNT" ] && [ "$TOTAL_COUNT" -gt 0 ]; then
    echo "エラー: 対象サービスのコンテナが1つも見つかりません(project=${COMPOSE_PROJECT})。" >&2
    echo "$PENDING" | sed 's/^/  - /' >&2
    echo "  次のいずれかです:" >&2
    echo "    1) スタックが起動していない  -> docker compose up -d" >&2
    echo "    2) composeプロジェクト名がずれている" >&2
    echo "       現在の解決結果: ${COMPOSE_PROJECT}" >&2
    echo "       COMPOSE_PROJECT_NAME を明示すると解決できます" >&2
    echo "  実際に存在するプロジェクト:" >&2
    docker ps -a --filter 'label=com.docker.compose.project' \
      --format '{{.Label "com.docker.compose.project"}}' 2>/dev/null \
      | grep -v '^$' | sort -u | sed 's/^/    - /' >&2 || true
    exit 1
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
  log "OK: 対象サービスは全てhealthyです。"
  # reverse-proxy を待った場合だけ、ホストからの到達性まで見る(issue #1038)。
  # `--services "gateway keycloak"` のように reverse-proxy を対象外にした呼び出しでは、
  # 公開URLへ届かなくてもそれは呼び出し側の意図なので検査しない。
  if [ "$WAIT_ALL" -eq 1 ] || echo " $TARGET_SERVICES " | grep -q ' reverse-proxy '; then
    check_base_url_reachable || exit 1
  fi
  exit 0
fi

echo "NG: healthyにならなかったサービスがあります。" \
  "'docker compose logs <service>' で原因を確認してください。" >&2
exit 1
