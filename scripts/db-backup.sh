#!/bin/bash
# Let's Blogアプリ自身のMySQLデータベースをmysqldumpでバックアップする(開発者向け簡易手段)。
#
# issue #785: 分割前の単一スキーマ(lets_blog)は削除したため、サービス別スキーマ9つ(ADR-0004)を
# まとめて1ファイルへダンプする。root で実行するのは、各サービス専用ユーザーが自分のスキーマ
# にしかアクセスできないため(スキーマ横断のバックアップ用に lbs_backup ユーザーもあるが、
# こちらは .env に root しか無い場合でも動く開発者向けの簡易手段として root を使う)。
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
# .env は bash として実行しない(空白やシェル特殊文字を含む値で壊れるため。#1603)。
# 必要な MYSQL_ROOT_PASSWORD だけを取り出し、前後を囲む同種の引用符1組は docker compose と同様に外す。
MYSQL_ROOT_PASSWORD="$(grep -m1 '^MYSQL_ROOT_PASSWORD=' "$ENV_FILE" | cut -d= -f2- || true)"
case "$MYSQL_ROOT_PASSWORD" in
  \"*\") MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:1:${#MYSQL_ROOT_PASSWORD}-2}" ;;
  \'*\') MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:1:${#MYSQL_ROOT_PASSWORD}-2}" ;;
esac
if [ -z "$MYSQL_ROOT_PASSWORD" ]; then
  echo "エラー: .env の MYSQL_ROOT_PASSWORD が未設定、または空です" >&2
  exit 1
fi

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "エラー: コンテナ ${CONTAINER} が見つかりません(docker compose up -d で起動していますか?)" >&2
  exit 1
fi

OUTPUT_PATH="${1:-$REPO_ROOT/backups/lets-blog-backup-$(date +%Y%m%d-%H%M%S).sql}"
mkdir -p "$(dirname "$OUTPUT_PATH")"

# サービス別スキーマ(ADR-0004)。増減したらここも更新すること
# (docker-compose.yml の BACKUP_MYSQL_SCHEMAS と揃える)。
SCHEMAS="lbs_identity lbs_project lbs_content lbs_media lbs_ai lbs_publishing lbs_analytics lbs_platform lbs_log"

# shellcheck disable=SC2086
docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$CONTAINER" \
  mysqldump --user=root --single-transaction --routines --triggers --databases $SCHEMAS \
  > "$OUTPUT_PATH"

echo "バックアップを作成しました: $OUTPUT_PATH"
echo "対象スキーマ: $SCHEMAS"
