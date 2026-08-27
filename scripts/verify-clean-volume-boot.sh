#!/bin/bash
# issue #668の受入基準検証手順の再現用スクリプト。
#
# `lets_blog_server_mysql_data`ボリューム(MySQLの永続データのみ。rabbitmq_data/
# keycloak_postgres/penpot_*/wordpress_sites等の他ボリュームは対象外)を削除した
# 完全にクリーンな状態から `docker compose up -d <target>`(既定はgateway。gatewayは
# api/identity/media/ai/content/analytics/project/log-writer/keycloakへ推移的に依存するため、
# 実質スタック全体を起動する)を実行し、全サービスがhealthyになることを確認する。
#
# 背景(#668): identityはlegacy-api(api)がFlywayで管理するlets_blogスキーマの
# role_permissions等のテーブルを前提にしている。以前はidentityがapiのマイグレーションを
# 待たずに起動し、かつapiはmedia/ai/content/analytics(いずれもidentity待ち)の起動待ちで、
# 空のMySQLボリュームから起動不能になる循環デッドロックがあった。#668の修正で
# legacy-schema-migrateサービス(一回限りのFlywayジョブ)を導入し、identity/api双方が
# その完了を待ってから起動するようにした(docker-compose.yml参照)。
#
# 破壊的操作を伴うため(mysql_data削除)、既定では対話確認を挟む。
# CI等の非対話実行では --yes を付けること。
#
# 使い方:
#   ./scripts/verify-clean-volume-boot.sh [--yes] [--target <compose-service>] [--timeout <秒>]
#
# 例:
#   ./scripts/verify-clean-volume-boot.sh --yes
#   ./scripts/verify-clean-volume-boot.sh --yes --target gateway --timeout 900
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
ENV_FILE="$REPO_ROOT/.env"

TARGET="gateway"
TIMEOUT_SECONDS=900
ASSUME_YES=0

while [ $# -gt 0 ]; do
  case "$1" in
    --yes)
      ASSUME_YES=1
      shift
      ;;
    --target)
      TARGET="$2"
      shift 2
      ;;
    --timeout)
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    *)
      echo "エラー: 不明な引数 '$1'" >&2
      exit 1
      ;;
  esac
done

if [ ! -f "$ENV_FILE" ]; then
  echo "エラー: $ENV_FILE が見つかりません(cp .env.example .env で作成してください)" >&2
  exit 1
fi

cd "$REPO_ROOT"

echo "=================================================================="
echo " 警告: このスクリプトは lets_blog_server_mysql_data ボリュームを"
echo " 削除し、MySQL上の全データ(投稿・ユーザー・設定等)を消去します。"
echo " スタックは一旦停止し、その後クリーンな状態から再起動します。"
echo "=================================================================="

if [ "$ASSUME_YES" -ne 1 ]; then
  read -r -p "続行しますか? MySQLデータが失われます [y/N]: " confirm
  case "$confirm" in
    [yY]|[yY][eE][sS]) ;;
    *)
      echo "中止しました。データは変更していません。"
      exit 1
      ;;
  esac
fi

echo "--- 1/5: スタックを停止します (docker compose down) ---"
docker compose down

echo "--- 2/5: mysql_data ボリュームを特定して削除します ---"
MYSQL_VOLUME_NAME="$(docker volume ls \
  --filter "label=com.docker.compose.volume=mysql_data" \
  --format '{{.Name}}' | head -n1)"

if [ -z "$MYSQL_VOLUME_NAME" ]; then
  echo "情報: com.docker.compose.volume=mysql_data ラベルを持つボリュームが見つかりません" \
    "(既に削除済み、または未起動の可能性)。次のステップへ進みます。"
else
  echo "削除対象: $MYSQL_VOLUME_NAME"
  docker volume rm "$MYSQL_VOLUME_NAME"
fi

echo "--- 3/5: クリーンな状態から起動します (docker compose up -d $TARGET) ---"
docker compose up -d "$TARGET"

echo "--- 4/5: 全サービスがhealthyになるまで待機します(最大 ${TIMEOUT_SECONDS}秒) ---"
START_TIME="$(date +%s)"
ALL_OK=0

while true; do
  NOW="$(date +%s)"
  ELAPSED=$((NOW - START_TIME))

  STATUS_JSON="$(docker compose ps --all --format json)"
  PENDING="$(echo "$STATUS_JSON" | python3 -c '
import sys, json

pending = []
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    c = json.loads(line)
    service = c.get("Service", "?")
    state = c.get("State", "")
    health = c.get("Health", "")
    exit_code = c.get("ExitCode", 0)

    # legacy-schema-migrateは一回限りのジョブなので、正常終了(exited, code 0)がゴール
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
')"

  if [ -z "$PENDING" ]; then
    ALL_OK=1
    break
  fi

  if [ "$ELAPSED" -ge "$TIMEOUT_SECONDS" ]; then
    echo "タイムアウト(${TIMEOUT_SECONDS}秒経過)。以下のサービスがまだhealthy/正常終了していません:"
    echo "$PENDING" | sed 's/^/  - /'
    break
  fi

  echo "[$ELAPSED s] 待機中: $(echo "$PENDING" | tr '\n' ', ')"
  sleep 5
done

echo "--- 5/5: 最終状態 ---"
docker compose ps

if [ "$ALL_OK" -eq 1 ]; then
  echo ""
  echo "OK: 全サービスがhealthy(またはlegacy-schema-migrateは正常終了)になりました。"
  exit 0
else
  echo ""
  echo "NG: healthyにならなかったサービスがあります。" \
    "'docker compose logs <service>' で原因を確認してください。"
  exit 1
fi
