#!/bin/bash
# mysqldumpで作成したバックアップファイルからLet's BlogアプリのMySQLデータベースをリストアする
# (開発者向け簡易手段)。既存データを上書きする破壊的操作のため、実行前に対話確認する。
#
# issue #785: db-backup.sh が --databases でサービス別スキーマ9つ(ADR-0004)をダンプするように
# なったため、ダンプ側に USE 文が含まれる。リストア時はスキーマ名を指定しない
# (指定するとダンプ内の USE と食い違う)。
#
# 注意: このスクリプトはDBのみが対象で、生成画像ファイル(generated_images ボリューム)は
# 復元しない。生成画像ファイルも含めた完全なリストアが必要な場合は、
# Web管理画面の「データバックアップ」ページ(/admin/backup)を使うこと。
#
# 使い方: ./scripts/db-restore.sh <db-backup.shで作成したバックアップファイルのパス>
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
ENV_FILE="$REPO_ROOT/.env"
CONTAINER="lbs-mysql"

if [ $# -ne 1 ]; then
  echo "使い方: $0 <バックアップファイルのパス>" >&2
  exit 1
fi
DUMP_FILE="$1"

if [ ! -f "$DUMP_FILE" ]; then
  echo "エラー: バックアップファイルが見つかりません: $DUMP_FILE" >&2
  exit 1
fi
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

RESTORE_TARGETS="$(grep -o '^USE `[^`]*`' "$DUMP_FILE" | sed 's/^USE `//;s/`$//' | tr '\n' ' ')"
echo "警告: バックアップに含まれる次のスキーマの既存データは全て上書きされます。"
echo "  ${RESTORE_TARGETS:-(USE文が見つかりません。単一スキーマのダンプの可能性があります)}"
read -r -p "続行しますか? (yes と入力してください): " CONFIRMATION
if [ "$CONFIRMATION" != "yes" ]; then
  echo "中止しました。"
  exit 1
fi

docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$CONTAINER" \
  mysql --user=root \
  < "$DUMP_FILE"

echo "リストアが完了しました: $DUMP_FILE"
