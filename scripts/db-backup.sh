#!/bin/bash
# Let's Blogアプリ自身のMySQLデータベースをmysqldumpでバックアップする(開発者向け簡易手段)。
# mysqlコンテナはportsを公開していないため、ホストから直接接続できず、
# connect-internet-egress.shと同様にコンテナ名を直接指定してdocker exec経由で操作する。
#
# 注意: このスクリプトはDBのみが対象で、生成画像ファイル(generated_images ボリューム)は
# 含まれない。生成画像ファイルも含めた完全なバックアップ/リストアが必要な場合は、
# Web管理画面の「データバックアップ」ページ(/admin/backup)を使うこと。
#
# 使い方: ./scripts/db-backup.sh [出力先パス(省略時は backups/lets-blog-backup-YYYYmmdd-HHMMSS.sql)]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
ENV_FILE="$REPO_ROOT/.env"
CONTAINER="lbs-mysql"

if [ ! -f "$ENV_FILE" ]; then
  echo "エラー: $ENV_FILE が見つかりません" >&2
  exit 1
fi
# shellcheck disable=SC1090
set -a
source "$ENV_FILE"
set +a

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "エラー: コンテナ ${CONTAINER} が見つかりません(docker compose up -d で起動していますか?)" >&2
  exit 1
fi

OUTPUT_PATH="${1:-$REPO_ROOT/backups/lets-blog-backup-$(date +%Y%m%d-%H%M%S).sql}"
mkdir -p "$(dirname "$OUTPUT_PATH")"

docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$CONTAINER" \
  mysqldump --user="$MYSQL_USER" --single-transaction --routines --triggers "$MYSQL_DATABASE" \
  > "$OUTPUT_PATH"

echo "バックアップを作成しました: $OUTPUT_PATH"
