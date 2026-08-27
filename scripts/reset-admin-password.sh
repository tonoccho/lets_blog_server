#!/bin/bash
# ログインできなくなった場合(SMTP未設定でメールによるパスワードリセットが使えない等)に、
# 対象ユーザーのパスワードだけを安全にリセットする運用スクリプト。
# 実行中のapiコンテナ内で一時的な別プロセスとしてSpring Bootアプリを起動し
# (admin-password-resetプロファイルによりWebサーバーは起動しない)、
# AdminPasswordResetRunner経由でKeycloak Admin REST APIを呼び出し、対象ユーザーのKeycloak側
# パスワードを即時変更する(temporary=false、次回ログイン時の強制変更なし。issue #681)。
# ログインはKeycloakへ一本化されているため、ローカルDBのpassword_hashのみを更新しても
# 実際にはログインできない(#564由来のギャップ)。対象ユーザーがKeycloak上に存在しない場合は
# エラーで終了し、DBのみを操作して成功したように見せることはない。
# 手動でのSQL操作や全ユーザー削除(TRUNCATE)は不要。
#
# 使い方: ./scripts/reset-admin-password.sh <email> <new-password>
set -euo pipefail

CONTAINER="lbs-api"

if [ "$#" -ne 2 ]; then
  echo "使い方: $0 <email> <new-password>" >&2
  exit 1
fi

EMAIL="$1"
NEW_PASSWORD="$2"

if [ "${#NEW_PASSWORD}" -lt 8 ]; then
  echo "エラー: パスワードは8文字以上である必要があります" >&2
  exit 1
fi

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "エラー: コンテナ ${CONTAINER} が見つかりません(docker compose up -d で起動していますか?)" >&2
  exit 1
fi

docker exec \
  -e ADMIN_RESET_EMAIL="$EMAIL" \
  -e ADMIN_RESET_PASSWORD="$NEW_PASSWORD" \
  "$CONTAINER" \
  java -jar /app/app.jar --spring.profiles.active=admin-password-reset

echo "パスワードをリセットしました: $EMAIL"
