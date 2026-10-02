#!/bin/bash
# 導入済み環境を最新にする1コマンドのアップデートスクリプト(issue #962)。
#
# 使い方:
#   ./update.sh                  # develop の最新を取り込む(既定)
#   ./update.sh --main           # main を更新元にする
#   ./update.sh --branch <name>  # 任意のブランチを更新元にする
#   ./update.sh --skip-backup    # 更新前バックアップを省略する
#   ./update.sh --rebuild-all    # 差分に関係なく全サービスを再ビルドする
#
# 行うこと(冪等。何度実行しても壊れない):
#   1. 更新元ブランチの表示(何かを変更する前に必ず表示する)
#   2. 安全確認(未コミットの変更がある、または現在のブランチが更新元と異なる場合は
#      何も変更せず中断する。stash や上書きはしない)
#   3. 更新前バックアップ(scripts/db-backup.sh)。失敗したら git pull せず中断する
#   4. git fetch + fast-forward のみの取り込み(分岐していれば中断する)
#   5. 取り込んだ差分から再ビルド対象のサービスだけを判定し(実行前に一覧表示)、
#      docker compose up -d --build <対象> で再ビルド・反映する。
#      判定が難しい変更(docker-compose.yml / build.gradle / settings.gradle / gradlew /
#      packages/ / gradle/ / config/ / services/*/build.gradle)は全サービス再ビルドに倒す
#   5.5 (取り込み直後・再ビルドの前)scripts/check-env.sh で .env が .env.example に追随して
#      いるか確認する。不足キーがあれば一覧を表示し、起動を試みず非0で終了する。
#      既存の .env の値は書き換えない。不足が無ければ何も表示しない(#1254)。
#      --fill-secrets を付けたときだけ、不足キーのうち自動生成してよい秘密値
#      (scripts/lib/env-secrets.sh。setup.sh と同じ分類)を .env の末尾へ追記する。
#      APIキー等の外部の値は追記せず、不足として報告する
#   6. scripts/wait-for-stack-healthy.sh --all で全サービスが healthy になるまで待つ
#      (setup.sh と同じ判定を再利用。失敗・タイムアウト時は非0で終了し、
#       更新前バックアップがあれば scripts/db-restore.sh での復旧手順を案内する)
#
# データボリュームには触れない(コンテナ・ボリュームを破棄する操作は一切使わない)。
#
# `source update.sh` で関数だけを読み込める(直接実行時のみ main を呼ぶ)。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR"
# 自動生成してよい秘密値の分類は setup.sh と共有する(#1254)
# shellcheck source=scripts/lib/env-secrets.sh
source "$REPO_ROOT/scripts/lib/env-secrets.sh"

TARGET_BRANCH="develop"
SKIP_BACKUP=0
REBUILD_ALL=0
FILL_SECRETS=0
OLD_HEAD=""
NEW_HEAD=""
REBUILD_MODE="all"     # all = 全サービス / selected = REBUILD_TARGETS のみ
REBUILD_TARGETS=()
BACKUP_PATH=""

log() { echo "==> $*"; }
err() { echo "エラー: $*" >&2; }

usage() {
  cat <<'USAGE'
使い方: ./update.sh [--main | --branch <name>] [--skip-backup] [--rebuild-all] [--fill-secrets]

  (指定なし)        develop の最新を取り込んで再ビルド・再起動する(既定)
  --main            main を更新元にする
  --skip-backup     更新前のDBバックアップを省略する
  --rebuild-all     差分に関係なく全サービスを再ビルドする
  --fill-secrets    .env に不足している秘密値のうち自動生成してよいものだけを追記する
                    (既定は報告のみ。既存の値は書き換えない。APIキー等の外部の値は追記しない)
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
      --skip-backup)
        SKIP_BACKUP=1
        shift
        ;;
      --rebuild-all)
        REBUILD_ALL=1
        shift
        ;;
      --fill-secrets)
        FILL_SECRETS=1
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

# ---- 更新前バックアップ(#1252) ----
backup_before_update() {
  if [ "$SKIP_BACKUP" = "1" ]; then
    log "--skip-backup が指定されたため、更新前バックアップを省略します"
    return 0
  fi
  local path="$REPO_ROOT/backups/pre-update-$(date +%Y%m%d-%H%M%S).sql"
  log "更新前にDBをバックアップします"
  if ! bash "$REPO_ROOT/scripts/db-backup.sh" "$path"; then
    err "バックアップに失敗したため、更新を中断します(git pull は実行していません)。"
    err "  バックアップを省略して更新する場合は --skip-backup を付けて再実行してください。"
    exit 1
  fi
  BACKUP_PATH="$path"
  log "バックアップ先: $BACKUP_PATH"
  log "注意: 生成画像ファイルはバックアップ対象外です(generated_images ボリュームは含まれません)。完全バックアップは Web 管理画面の /admin/backup を使ってください。"
}

# 更新後に失敗したとき、直前のバックアップからの復旧手順を案内する
guide_restore() {
  if [ -n "$BACKUP_PATH" ]; then
    err "直前のバックアップから復旧する場合: bash scripts/db-restore.sh $BACKUP_PATH"
  else
    err "更新前バックアップを取っていない(--skip-backup)ため、このスクリプトからは復旧できません。"
  fi
}

# ---- 取り込み(fast-forwardのみ) ----
pull_latest() {
  log "origin から取得します"
  OLD_HEAD="$(git -C "$REPO_ROOT" rev-parse HEAD)"
  if ! git -C "$REPO_ROOT" fetch origin "$TARGET_BRANCH"; then
    err "git fetch に失敗しました。"
    exit 1
  fi
  if ! git -C "$REPO_ROOT" merge --ff-only "origin/$TARGET_BRANCH"; then
    err "fast-forward で取り込めません(ローカルが origin/$TARGET_BRANCH と分岐しています)。"
    err "  ローカルのコミットを確認してください。自動では解決しません。"
    exit 1
  fi
  NEW_HEAD="$(git -C "$REPO_ROOT" rev-parse HEAD)"
}

# ---- .env 追随チェック(#1254) ----
# .env.example にあって .env に無いキーのうち、自動生成してよい秘密値だけを .env の末尾へ追記する。
# 既存の行には触れない。生成した値は表示せず、キー名だけを知らせる。
fill_missing_secrets() {
  local example="$REPO_ROOT/.env.example" target="$REPO_ROOT/.env"
  [ -f "$example" ] && [ -f "$target" ] || return 0
  local filled=() line key value
  while IFS= read -r line || [ -n "$line" ]; do
    [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    grep -qE "^${key}=" "$target" && continue
    is_auto_generatable_secret "$key" "$value" || continue
    # 末尾に改行が無い .env でも既存の最終行を壊さない
    if [ -s "$target" ] && [ -n "$(tail -c1 "$target")" ]; then
      printf '\n' >>"$target"
    fi
    printf '%s=%s\n' "$key" "$(generate_value_for_key "$key")" >>"$target"
    filled+=("$key")
  done <"$example"
  if [ "${#filled[@]}" -gt 0 ]; then
    log ".env に自動生成した秘密値を追記しました: ${filled[*]}"
  fi
}

# .env が .env.example に追随しているか確認する。不足があれば一覧して中断する(起動しない)。
# 不足が無いときは何も表示しない。
check_env_up_to_date() {
  if [ "$FILL_SECRETS" = "1" ]; then
    fill_missing_secrets
  fi
  local out
  # LC_ALL=C: check-env.sh は sort と comm を併用する。ロケールによっては両者の照合順が食い違い
  # "comm: file 1 is not in sorted order" で誤検知する(A_KEY と API_KEY など)ため、C に固定する。
  if out="$(LC_ALL=C bash "$REPO_ROOT/scripts/check-env.sh" 2>&1)"; then
    return 0
  fi
  echo "$out" >&2
  err ".env が .env.example に追随していないため、再起動せずに中断します(.env は書き換えていません)。"
  err "  不足キーを .env に追記して ./update.sh を再実行してください(取り込みは済んでいるので再実行は安全です)。"
  if [ "$FILL_SECRETS" != "1" ]; then
    err "  自動生成できる秘密値だけなら --fill-secrets で追記できます(APIキー等の外部の値は自分で設定してください)。"
  fi
  exit 1
}

# ---- 再ビルド対象の判定(#1253) ----
# docker-compose.yml の build: から「サービス名|context|dockerfile」を1行ずつ出す。
list_build_services() {
  awk '
    /^services:/ { s = 1; next }
    /^[^ #]/ { s = 0 }
    s && /^  [A-Za-z0-9_-]+:[ ]*$/ { name = $1; sub(/:$/, "", name); ctx = "."; inb = 0; next }
    s && /^    build:[ ]*$/ { inb = 1; next }
    s && /^    [^ ]/ { inb = 0 }
    s && inb && /^      context:/ { ctx = $2 }
    s && inb && /^      dockerfile:/ { print name "|" ctx "|" $2 }
  ' "$REPO_ROOT/docker-compose.yml"
}

# 全サービスの再ビルドに倒す(判定が難しい)共有ファイルか
is_shared_build_input() {
  case "$1" in
    docker-compose.yml|build.gradle|settings.gradle|gradlew) return 0 ;;
    packages/*|gradle/*|config/*) return 0 ;;
    services/*/build.gradle) return 0 ;;
  esac
  return 1
}

# サービスが専有するディレクトリ(context: . の Java サービスは Dockerfile の置き場)
service_dir() {
  local ctx="$1" dockerfile="$2"
  if [ "$ctx" = "." ]; then
    dirname "$dockerfile"
  else
    ctx="${ctx#./}"
    echo "${ctx%/}"
  fi
}

decide_rebuild_targets() {
  REBUILD_MODE="selected"
  REBUILD_TARGETS=()
  local services
  if [ ! -f "$REPO_ROOT/docker-compose.yml" ] || ! services="$(list_build_services)" || [ -z "$services" ]; then
    log "build 対象を判定できないため、全サービスを再ビルドします"
    REBUILD_MODE="all"
    return 0
  fi
  if [ "$REBUILD_ALL" = "1" ]; then
    REBUILD_MODE="all"
  elif [ "$OLD_HEAD" != "$NEW_HEAD" ]; then
    local f name ctx dockerfile dir
    while IFS= read -r f; do
      if is_shared_build_input "$f"; then
        REBUILD_MODE="all"
        break
      fi
    done < <(git -C "$REPO_ROOT" diff --name-only "$OLD_HEAD" "$NEW_HEAD")
    if [ "$REBUILD_MODE" = "selected" ]; then
      while IFS='|' read -r name ctx dockerfile; do
        dir="$(service_dir "$ctx" "$dockerfile")"
        if git -C "$REPO_ROOT" diff --name-only "$OLD_HEAD" "$NEW_HEAD" -- "$dir/" | grep -q .; then
          REBUILD_TARGETS+=("$name")
        fi
      done <<< "$services"
    fi
  fi
  if [ "$REBUILD_MODE" = "all" ]; then
    REBUILD_TARGETS=()
    while IFS='|' read -r name ctx dockerfile; do
      REBUILD_TARGETS+=("$name")
    done <<< "$services"
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
  decide_rebuild_targets
  local up_args=(up -d)
  if [ "$REBUILD_MODE" = "all" ]; then
    log "再ビルド対象(全サービス ${#REBUILD_TARGETS[@]}件): ${REBUILD_TARGETS[*]:-なし}"
    up_args+=(--build)
  elif [ "${#REBUILD_TARGETS[@]}" -eq 0 ]; then
    log "再ビルド対象: 0件(再ビルドは行いません)"
  else
    log "再ビルド対象(${#REBUILD_TARGETS[@]}件): ${REBUILD_TARGETS[*]}"
    up_args+=(--build "${REBUILD_TARGETS[@]}")
  fi
  if ! (cd "$REPO_ROOT" && docker compose "${up_args[@]}"); then
    err "docker compose ${up_args[*]} に失敗しました。上記の出力を確認してください。"
    guide_restore
    exit 1
  fi
  log "全サービスが healthy になるまで待機します"
  if ! bash "$REPO_ROOT/scripts/wait-for-stack-healthy.sh" --all; then
    err "healthy にならないサービスがあります(上記に該当サービス名が出ています)。更新は完了していません。"
    guide_restore
    exit 1
  fi
}

main() {
  parse_args "$@"
  log "更新元ブランチ: $TARGET_BRANCH"
  check_clean_and_branch
  backup_before_update
  pull_latest
  check_env_up_to_date
  rebuild_and_wait
  log "アップデートが完了しました。"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
