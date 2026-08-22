#!/bin/bash
# サービス別スキーマ分離(#570, ADR-0004)。MySQLコンテナの初回起動時
# (docker-entrypoint-initdb.d、データボリュームが空の場合のみ実行される)に、
# サービスごとのスキーマと専用ユーザーを作成する。各ユーザーは自分のスキーマにしか
# アクセスできない(サービス跨ぎのJOIN/FKは禁止 - ADR-0004)。
#
# 注意: 既存のMySQLデータボリュームが既にある環境では、コンテナを再作成しても
# このスクリプトは実行されない(MySQL公式イメージの仕様)。適用するにはボリュームの
# 再作成が必要(docs/adr/0004-schema-per-service.md, docs/MIGRATION_TESTING.md 参照)。
set -euo pipefail

# schema名 => パスワードを渡す環境変数名
declare -A SCHEMA_PASSWORD_ENV=(
  [lbs_identity]="LBS_IDENTITY_DB_PASSWORD"
  [lbs_project]="LBS_PROJECT_DB_PASSWORD"
  [lbs_content]="LBS_CONTENT_DB_PASSWORD"
  [lbs_media]="LBS_MEDIA_DB_PASSWORD"
  [lbs_ai]="LBS_AI_DB_PASSWORD"
  [lbs_publishing]="LBS_PUBLISHING_DB_PASSWORD"
  [lbs_analytics]="LBS_ANALYTICS_DB_PASSWORD"
  [lbs_platform]="LBS_PLATFORM_DB_PASSWORD"
  [lbs_log]="LBS_LOG_DB_PASSWORD"
)

for schema in "${!SCHEMA_PASSWORD_ENV[@]}"; do
  password_env="${SCHEMA_PASSWORD_ENV[$schema]}"
  password="${!password_env:-}"
  if [ -z "$password" ]; then
    echo "警告: ${password_env} が未設定のため ${schema} のユーザー作成をスキップします" >&2
    continue
  fi

  mysql -u root -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
    CREATE DATABASE IF NOT EXISTS \`${schema}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE USER IF NOT EXISTS '${schema}'@'%' IDENTIFIED BY '${password}';
    GRANT ALL PRIVILEGES ON \`${schema}\`.* TO '${schema}'@'%';
EOSQL
  echo "スキーマ ${schema} とユーザー ${schema}@% を作成しました"
done

mysql -u root -p"${MYSQL_ROOT_PASSWORD}" -e "FLUSH PRIVILEGES;"
