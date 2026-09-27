#!/bin/bash
# 導入済み環境を最新にする1コマンドのアップデートスクリプト(issue #962)。
#
# 使い方:
#   ./update.sh                  # develop の最新を取り込む(既定)
#   ./update.sh --main           # main を更新元にする
#   ./update.sh --branch <name>  # 任意のブランチを更新元にする
#
# 行うこと(冪等。何度実行しても壊れない):
#   1. 更新元ブランチの表示(何かを変更する前に必ず表示する)
#   2. 安全確認(未コミットの変更がある、または現在のブランチが更新元と異なる場合は
#      何も変更せず中断する。stash や上書きはしない)
#   3. git fetch + fast-forward のみの取り込み(分岐していれば中断する)
#   4. docker compose up -d --build で全サービスを再ビルド・反映
#   5. scripts/wait-for-stack-healthy.sh --all で全サービスが healthy になるまで待つ
#      (setup.sh と同じ判定を再利用。失敗・タイムアウト時は非0で終了する)
#
# データボリュームには触れない(コンテナ・ボリュームを破棄する操作は一切使わない)。
# 更新前バックアップ・選択的再ビルド・.env 追随チェックは対象外(#1252/#1253/#1254)。
#
# `source update.sh` で関数だけを読み込める(直接実行時のみ main を呼ぶ)。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR"

TARGET_BRANCH="develop"

log() { echo "==> $*"; }
err() { echo "エラー: $*" >&2; }

usage() {
  cat <<'USAGE'
使い方: ./update.sh [--main | --branch <name>]

  (指定なし)        develop の最新を取り込んで再ビルド・再起動する(既定)
  --main            main を更新元にする
  --branch <name>   任意のブランチを更新元にする
  -h, --help        このヘルプを表示する
USAGE
}

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --branch)
        if [ $# -lt 2 ]; then
          err "--branch にはブランチ名が必要です"
          exit 1
        fi
        TARGET_BRANCH="$2"
        shift 2
        ;;
      --main)
        TARGET_BRANCH="main"
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

# ---- 安全確認 ----
check_clean_and_branch() {
  local current
  current="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")"
  if [ "$current" != "$TARGET_BRANCH" ]; then
    err "現在のブランチ '${current:-不明}' が更新元ブランチ '$TARGET_BRANCH' と異なります。何も変更せず中断します。"
    err "  'git checkout $TARGET_BRANCH' してから再実行するか、--branch <name> / --main で更新元を指定してください。"
    exit 1
  fi
  if [ -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=no)" ]; then
    err "未コミットの変更があります。何も変更せず中断します(stash や上書きは行いません)。"
    err "  コミットまたは退避してから再実行してください。"
    exit 1
  fi
  log "安全確認OK: ブランチ $current / 未コミットの変更なし"
}

# ---- 取り込み(fast-forwardのみ) ----
pull_latest() {
  log "origin から取得します"
  if ! git -C "$REPO_ROOT" fetch origin "$TARGET_BRANCH"; then
    err "git fetch に失敗しました。"
    exit 1
  fi
  if ! git -C "$REPO_ROOT" merge --ff-only "origin/$TARGET_BRANCH"; then
    err "fast-forward で取り込めません(ローカルが origin/$TARGET_BRANCH と分岐しています)。"
    err "  ローカルのコミットを確認してください。自動では解決しません。"
    exit 1
  fi
}

# #1084: buildx の permission denied を避けるため、専用の Docker 設定があれば使う。
# 利用者が DOCKER_CONFIG を設定済みならそれを尊重する。
setup_docker_config() {
  if [ -z "${DOCKER_CONFIG:-}" ] && [ -d "$HOME/.config/docker-cli" ]; then
    export DOCKER_CONFIG="$HOME/.config/docker-cli"
  fi
}

# ---- 再ビルド・反映とヘルス確認 ----
rebuild_and_wait() {
  setup_docker_config
  log "docker compose で全サービスを再ビルド・反映します"
  if ! (cd "$REPO_ROOT" && docker compose up -d --build); then
    err "docker compose up -d --build に失敗しました。上記の出力を確認してください。"
    exit 1
  fi
  log "全サービスが healthy になるまで待機します"
  if ! bash "$REPO_ROOT/scripts/wait-for-stack-healthy.sh" --all; then
    err "healthy にならないサービスがあります(上記に該当サービス名が出ています)。更新は完了していません。"
    exit 1
  fi
}

main() {
  parse_args "$@"
  log "更新元ブランチ: $TARGET_BRANCH"
  check_clean_and_branch
  pull_latest
  rebuild_and_wait
  log "アップデートが完了しました。"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
