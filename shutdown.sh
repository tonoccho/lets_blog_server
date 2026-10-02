#!/bin/bash
# データを消さずにシステムを停止する(issue #1528)。
#
# 使い方:
#   ./shutdown.sh            # コンテナを停止・削除する。名前付きボリューム(DB・Keycloak・生成画像など)は残る
#   ./shutdown.sh --volumes  # ボリュームも削除する(全データが消える)。消える一覧を示し、確認を求める
#
# 起動していないときは、その旨を表示して終了コード0で終わる。
# `-v` の1文字エイリアスは意図的に持たない(`docker compose down -v` を知っている人の誤爆防止)。
# 確認を飛ばす非対話フラグは持たない。
#
# 対象はベースの docker-compose.yml のみ(startup.sh と同じ)。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR"

# shellcheck source=scripts/lib/compose-project.sh
source "$REPO_ROOT/scripts/lib/compose-project.sh"

REMOVE_VOLUMES=0

log() { echo "==> $*"; }
err() { echo "エラー: $*" >&2; }

usage() {
  cat <<'USAGE'
使い方: ./shutdown.sh [--volumes]

  (指定なし)   コンテナを停止・削除する。ボリューム(DB・Keycloak・生成画像など)は残る
  --volumes    ボリュームも削除する。全データが消える。消えるボリュームを示し、確認を求める
  -h, --help   このヘルプを表示する
USAGE
}

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --volumes)
        REMOVE_VOLUMES=1
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

compose() {
  docker compose -p "$COMPOSE_PROJECT" -f "$REPO_ROOT/docker-compose.yml" "$@"
}

confirm_volume_removal() {
  echo "次のボリュームが削除され、中のデータは復元できません:"
  compose config --volumes | sed 's/^/  - /'
  local answer=""
  read -r -p "本当に削除しますか? 削除するなら yes と入力してください: " answer || true
  if [ "$answer" != "yes" ]; then
    err "確認が得られなかったため、何も削除せずに終了します。"
    exit 1
  fi
}

main() {
  parse_args "$@"
  COMPOSE_PROJECT="$(resolve_compose_project "$REPO_ROOT")"

  if [ "$REMOVE_VOLUMES" -eq 1 ]; then
    confirm_volume_removal
    log "停止: docker compose down --volumes"
    compose down --volumes
    log "停止しました。ボリュームも削除しました。"
    return 0
  fi

  if [ -z "$(compose ps -q)" ]; then
    log "起動していません。何もしません。"
    return 0
  fi
  log "停止: docker compose down(ボリュームは残します)"
  compose down
  log "停止しました。ボリューム(DB・Keycloak・生成画像など)は残っています。"
}

main "$@"
