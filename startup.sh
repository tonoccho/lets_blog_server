#!/bin/bash
# セットアップ済み環境を1コマンドで起動する(issue #961)。
#
# 使い方:
#   ./startup.sh            # 必要ならビルドして起動し、全サービスが healthy になるまで待つ
#   ./startup.sh --build    # 明示的に再ビルドしてから起動する
#
# 行うこと(冪等。起動済みで再実行しても壊れない):
#   1. ビルド   : --build 指定時、またはイメージが存在しないときだけ docker compose build
#   2. 起動     : docker compose up -d
#   3. ヘルス待ち: scripts/wait-for-stack-healthy.sh --all を再利用(自前のポーリングは持たない)。
#      healthy にならないサービスがあれば、そのサービス名が表示され、終了コード非0で終わる。
#
# 対象はベースの docker-compose.yml のみ(オーバーレイは適用しない)。
# コンテナ・ボリュームを破棄する操作は一切使わない。
# 起動前の事前確認(.env / 証明書 / docker の有無)は対象外(#1527)。
#
# `source startup.sh` で関数だけを読み込める(直接実行時のみ main を呼ぶ)。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR"

# shellcheck source=scripts/lib/compose-project.sh
source "$REPO_ROOT/scripts/lib/compose-project.sh"

FORCE_BUILD=0

log() { echo "==> $*"; }
err() { echo "エラー: $*" >&2; }

usage() {
  cat <<'USAGE'
使い方: ./startup.sh [--build]

  (指定なし)   必要ならビルドして起動し、全サービスが healthy になるまで待つ
  --build      明示的に再ビルドしてから起動する
  -h, --help   このヘルプを表示する
USAGE
}

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --build)
        FORCE_BUILD=1
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        err "不明な引数 '$1'"
        usage >&2
        exit 1
        ;;
    esac
  done
}

# #1084: buildx の permission denied を避けるため、専用の Docker 設定があれば使う。
# 利用者が DOCKER_CONFIG を設定済みならそれを尊重する。
setup_docker_config() {
  if [ -z "${DOCKER_CONFIG:-}" ] && [ -d "$HOME/.config/docker-cli" ]; then
    export DOCKER_CONFIG="$HOME/.config/docker-cli"
  fi
}

compose() {
  docker compose -p "$COMPOSE_PROJECT" -f "$REPO_ROOT/docker-compose.yml" "$@"
}

# compose が参照するイメージのうち、ローカルに存在しないものがあれば 0 を返す。
images_missing() {
  local image
  while IFS= read -r image; do
    [ -n "$image" ] || continue
    if ! docker image inspect "$image" >/dev/null 2>&1; then
      return 0
    fi
  done < <(compose config --images)
  return 1
}

build_stage() {
  if [ "$FORCE_BUILD" -eq 1 ]; then
    log "[1/3] ビルド: --build が指定されたため再ビルドします"
  elif images_missing; then
    log "[1/3] ビルド: 存在しないイメージがあるためビルドします(初回は時間がかかります)"
  else
    log "[1/3] ビルド: 必要なイメージは揃っているためスキップします"
    return 0
  fi
  if ! compose build; then
    err "docker compose build に失敗しました。上記の出力を確認してください。"
    exit 1
  fi
}

start_stage() {
  log "[2/3] 起動: docker compose up -d"
  if ! compose up -d; then
    err "docker compose up -d に失敗しました。上記の出力を確認してください。"
    exit 1
  fi
}

wait_stage() {
  log "[3/3] ヘルス待ち: 全サービスが healthy になるまで待機します"
  if ! bash "$REPO_ROOT/scripts/wait-for-stack-healthy.sh" --all; then
    err "healthy にならないサービスがあります(上記に該当サービス名が出ています)。起動は完了していません。"
    exit 1
  fi
}

print_success() {
  echo
  log "起動が完了しました。"
  echo "  https://localhost にアクセスしてください(自己署名証明書の警告は例外承認する)。"
  echo "  まだユーザーが1人も存在しない場合は /setup にリダイレクトされ、"
  echo "  セルフサインアップで最初のユーザー(管理者権限)を作成できます。"
}

main() {
  parse_args "$@"
  setup_docker_config
  COMPOSE_PROJECT="$(resolve_compose_project "$REPO_ROOT")"
  build_stage
  start_stage
  wait_stage
  print_success
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
