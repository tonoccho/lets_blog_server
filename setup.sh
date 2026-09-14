#!/bin/bash
# クローン直後の未導入Ubuntu/Debian機を「https://localhost が使える状態」まで
# 1コマンドで進める初回セットアップスクリプト(issue #960)。
#
# 使い方:
#   git clone -b develop ssh://git@server.tonoccho.local:2222/seiji/lets_blog_server.git
#   cd lets_blog_server
#   ./setup.sh
#
# 既定では develop ブランチ上で実行されていることを要求する(#962と既定を揃える)。
# 他ブランチで実行するには明示的に上書きする:
#   ./setup.sh --branch <name>   # 任意のブランチを指定
#   ./setup.sh --main            # main を明示的に使う
#
# 行うこと(冪等。何度実行しても壊れない):
#   1. 実行ブランチの確認(既定 develop。--branch / --main で上書き)
#   2. OS確認(Ubuntu/Debian系のみ)
#   3. 前提ソフトの導入(git/curl/openssl、Docker Engine + Compose v2、dockerグループ、
#      NVIDIA Container Toolkit(nvidia-smiが通る場合のみ))
#   4. .env の生成(既存があれば上書きしない。無ければ .env.example から自動生成できる
#      秘密値を生成して書き込み、外部サービスの値は空のまま残す)
#   5. TLS証明書の生成(scripts/generate-certs.sh を再利用)
#   6. スタックの起動とヘルス確認(scripts/wait-for-stack-healthy.sh --all を再利用。
#      #961のstartup.shと健全性判定ロジックを重複実装しない)
#
# 非対話で完走する(sudoのパスワード入力を除く)。失敗した箇所はログで分かる。
#
# テスト用フック(scripts/test_setup_sh.py):
#   SETUP_SH_OS_RELEASE_FILE  /etc/os-release の代わりに読むファイル(既定 /etc/os-release)
#   本スクリプトは末尾で直接実行時のみ main を呼ぶため、`source setup.sh` で
#   個々の関数(check_branch 等)だけを読み込んで単体に検証できる。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR"

TARGET_BRANCH="develop"

log() { echo "==> $*"; }
warn() { echo "警告: $*" >&2; }
err() { echo "エラー: $*" >&2; }

usage() {
  cat <<'USAGE'
使い方: ./setup.sh [--branch <name> | --main]

  --branch <name>   実行を許可するブランチを明示的に指定する(既定: develop)
  --main            main ブランチでの実行を許可する(--branch main と同じ)
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
        usage
        exit 1
        ;;
    esac
  done
}

# ---- 1. 実行ブランチの確認 ----
check_branch() {
  local current
  current="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")"
  if [ "$current" != "$TARGET_BRANCH" ]; then
    err "現在のブランチ '${current:-不明}' が想定ブランチ '$TARGET_BRANCH' と異なります。"
    err "  想定どおり develop から導入する場合は 'git checkout develop' してから再実行してください。"
    err "  develop 以外を明示的に使う場合は --branch <name> または --main を指定してください。"
    exit 1
  fi
  log "ブランチ確認OK: $current"
}

# ---- 2. OS確認(Ubuntu/Debian系のみ) ----
check_os() {
  local os_release="${SETUP_SH_OS_RELEASE_FILE:-/etc/os-release}"
  if [ ! -r "$os_release" ]; then
    err "$os_release を読み取れません。Ubuntu/Debian系のみサポートしています。"
    err "  手動導入手順は README.md の「前提ソフトウェアのインストール」を参照してください。"
    exit 1
  fi
  local id id_like
  id="$(. "$os_release" 2>/dev/null; echo "${ID:-}")"
  id_like="$(. "$os_release" 2>/dev/null; echo "${ID_LIKE:-}")"
  case " $id $id_like " in
    *" ubuntu "*|*" debian "*)
      log "OS確認OK: ID=$id ID_LIKE=$id_like"
      ;;
    *)
      err "未対応のOSです(ID=$id, ID_LIKE=$id_like)。Ubuntu/Debian系のみサポートしています。"
      err "  手動導入手順は README.md の「前提ソフトウェアのインストール」を参照してください。"
      exit 1
      ;;
  esac
}

# ---- 3. 前提ソフトの導入 ----
APT_UPDATED=0
apt_update_once() {
  if [ "$APT_UPDATED" -eq 0 ]; then
    sudo apt-get update
    APT_UPDATED=1
  fi
}

# $1 = aptパッケージ名, $2 = 存在確認に使うコマンド名(省略時は$1と同じ)
ensure_apt_package() {
  local pkg="$1" cmd="${2:-$1}"
  if command -v "$cmd" >/dev/null 2>&1; then
    log "$cmd は導入済みです"
    return 0
  fi
  log "$pkg を導入します"
  apt_update_once
  sudo apt-get install -y "$pkg"
}

DOCKER_GROUP_JUST_ADDED=0

ensure_docker_group() {
  local target_user="${SUDO_USER:-$USER}"
  if id -nG "$target_user" 2>/dev/null | tr ' ' '\n' | grep -qx docker; then
    log "$target_user は docker グループに所属済みです"
    return 0
  fi
  log "$target_user を docker グループに追加します"
  sudo usermod -aG docker "$target_user"
  DOCKER_GROUP_JUST_ADDED=1
  warn "docker グループへの追加はこの実行内では sg docker 経由で反映します。次回以降のシェルで有効にするには再ログイン(またはシェルで newgrp docker)が必要です。"
}

ensure_docker() {
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    log "Docker Engine / Compose v2 は導入済みです: $(docker --version)"
  else
    log "Docker Engine + Compose v2 を導入します(公式インストールスクリプト)"
    curl -fsSL https://get.docker.com | sh
  fi
  ensure_docker_group
}

# NVIDIA Container Toolkitは nvidia-smi が通る場合のみ導入する。
# GPUが無い環境では導入をスキップし、警告を出したうえで正常終了する
# (README「ハードウェア要件」の記述に合わせる)。GPUドライバ自体の導入は行わない。
ensure_nvidia_toolkit() {
  if ! command -v nvidia-smi >/dev/null 2>&1 || ! nvidia-smi >/dev/null 2>&1; then
    warn "nvidia-smi が使えないため NVIDIA Container Toolkit の導入をスキップします。"
    warn "  GPUドライバが無い/未導入の環境では、画像生成(ComfyUI)が実用的な速度で動作しません。"
    return 0
  fi
  if dpkg -s nvidia-container-toolkit >/dev/null 2>&1; then
    log "NVIDIA Container Toolkit は導入済みです"
    return 0
  fi
  log "NVIDIA Container Toolkit を導入します"
  curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey | sudo gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
  curl -s -L https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list | \
    sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' | \
    sudo tee /etc/apt/sources.list.d/nvidia-container-toolkit.list >/dev/null
  apt_update_once
  sudo apt-get install -y nvidia-container-toolkit
  sudo nvidia-ctk runtime configure --runtime=docker
  sudo systemctl restart docker
}

install_prerequisites() {
  ensure_apt_package git
  ensure_apt_package curl
  ensure_apt_package openssl
  ensure_docker
  ensure_nvidia_toolkit
}

# ---- 4. .env の生成 ----
#
# .env.example を情報源とする(#960 Implementation Notes)。キーの分類は値そのもので
# 表す(.env.example側が契約):
#   - 値が "changeme_" で始まる  -> 何でもよい内部秘密値。ここで生成して埋める
#   - 値が空                    -> 利用者しか知り得ない外部の値。生成せず空のまま残す
#   - それ以外                  -> 変更不要な既定値(コメント・並びごとそのままコピー)
#
# 例外: APP_ENCRYPTION_KEY は "changeme_" ではなく、CredentialCipher(packages/lbs-common)が
# strict Base64として要求するダミーの有効値を既定にしている(check-env.shの特別扱いと同じ理由)。
# 例外: KEYCLOAK_SERVICES_CLIENT_SECRET / KEYCLOAK_WEB_CLIENT_SECRET は "changeme_" ではなく、
# infra/keycloak/realm-export.json の `secret` と一致させる必要がある既定値
# (.env.example のコメント参照)。ここで無条件に再生成すると realm import 済みの値と食い違い、
# invalid_client でログインできなくなるため、setup.sh は変更しない。
APP_ENCRYPTION_KEY_PLACEHOLDER="UkVQTEFDRV9XSVRIX09QRU5TU0xfUkFORF9CNjRfMzI="

generate_value_for_key() {
  local key="$1"
  case "$key" in
    APP_ENCRYPTION_KEY) openssl rand -base64 32 ;;
    NEXTAUTH_SECRET) openssl rand -hex 32 ;;
    PENPOT_SECRET_KEY) openssl rand -base64 64 | tr -d '\n' ;;
    WP_PROVISION_TOKEN) openssl rand -hex 32 ;;
    *) openssl rand -base64 24 ;;
  esac
}

# .env.example から .env を生成する。コメント行・空行・並びはそのまま保つ。
generate_env_file() {
  local example="$REPO_ROOT/.env.example"
  local target="$REPO_ROOT/.env"
  local tmp
  tmp="$(mktemp)"

  while IFS= read -r line || [ -n "$line" ]; do
    if [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      local key="${BASH_REMATCH[1]}"
      local value="${BASH_REMATCH[2]}"
      local new_value="$value"
      if [[ "$value" == changeme_* ]]; then
        new_value="$(generate_value_for_key "$key")"
      elif [ "$key" = "APP_ENCRYPTION_KEY" ] && [ "$value" = "$APP_ENCRYPTION_KEY_PLACEHOLDER" ]; then
        new_value="$(generate_value_for_key "$key")"
      fi
      printf '%s=%s\n' "$key" "$new_value" >>"$tmp"
    else
      printf '%s\n' "$line" >>"$tmp"
    fi
  done <"$example"

  mv "$tmp" "$target"
  chmod 600 "$target"
}

# .env の中で値が空のキー(=利用者が自分で用意しなければならない外部の値)を列挙する。
list_unset_external_keys() {
  local target="$REPO_ROOT/.env"
  grep -oE '^[A-Za-z_][A-Za-z0-9_]*=$' "$target" | sed 's/=$//' | sort -u
}

ensure_env_file() {
  local target="$REPO_ROOT/.env"
  if [ -f "$target" ]; then
    log ".env は既に存在するため上書きしません。"
    if ! bash "$REPO_ROOT/scripts/check-env.sh"; then
      warn "上記の不足キーを .env.example を参照して手動で追記してください。"
    fi
    return 0
  fi

  log ".env.example から .env を生成します"
  generate_env_file

  if ! bash "$REPO_ROOT/scripts/check-env.sh"; then
    err ".env の生成後に check-env.sh が失敗しました。上記の内容を確認してください。"
    exit 1
  fi

  local unset_keys
  unset_keys="$(list_unset_external_keys)"
  if [ -n "$unset_keys" ]; then
    log "以下は利用者自身が用意する外部の値のため、生成せず空のままにしています(使う場合は手動で .env に設定してください):"
    echo "$unset_keys" | sed 's/^/    - /'
  fi
}

# ---- 5. TLS証明書 ----
ensure_certs() {
  bash "$REPO_ROOT/scripts/generate-certs.sh"
}

# ---- 6. 起動とヘルス確認 ----
# #961(startup.sh)の起動ロジックと重複実装しない。docker compose up と
# scripts/wait-for-stack-healthy.sh --all をそのまま呼ぶ。
start_stack_and_wait() {
  local cmd
  cmd="cd $(printf '%q' "$REPO_ROOT") && docker compose up -d --build && bash $(printf '%q' "$REPO_ROOT/scripts/wait-for-stack-healthy.sh") --all"

  log "Docker Composeでスタックを起動し、全サービスがhealthyになるまで待機します"
  if [ "$DOCKER_GROUP_JUST_ADDED" -eq 1 ]; then
    log "docker グループへの追加をこの実行内で反映するため sg docker 経由で実行します"
    if ! sg docker -c "$cmd"; then
      err "スタックの起動、または起動待機に失敗しました。上記の出力を確認してください。"
      exit 1
    fi
  else
    if ! bash -c "$cmd"; then
      err "スタックの起動、または起動待機に失敗しました。上記の出力を確認してください。"
      exit 1
    fi
  fi
}

print_success() {
  echo
  log "セットアップが完了しました。"
  echo "  https://localhost にアクセスしてください(自己署名証明書の警告は例外承認する)。"
  echo "  まだユーザーが1人も存在しない場合は /setup にリダイレクトされ、"
  echo "  セルフサインアップで最初のユーザー(管理者権限)を作成できます。"
}

main() {
  parse_args "$@"
  check_branch
  check_os
  install_prerequisites
  ensure_env_file
  ensure_certs
  start_stack_and_wait
  print_success
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
