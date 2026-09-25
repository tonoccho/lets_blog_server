#!/bin/bash
# サービス別スキーマ分離(#570, ADR-0004)。MySQLコンテナの初回起動時
# (docker-entrypoint-initdb.d、データボリュームが空の場合のみ実行される)に、
# サービスごとのスキーマと専用ユーザーを作成する。各ユーザーは自分のスキーマにしか
# アクセスできない(サービス跨ぎのJOIN/FKは禁止 - ADR-0004)。
#
# 注意: 既存のMySQLデータボリュームが既にある環境では、コンテナを再作成しても
# このスクリプト自体は自動実行されない(MySQL公式イメージの仕様)。新しいスキーマを
# 追加した場合に反映するにはボリュームの再作成が必要(docs/adr/0004-schema-per-service.md,
# docs/MIGRATION_TESTING.md 参照)。
#
# パスワード変更時(#667): 本スクリプトはCREATE USERに加えてALTER USERも実行するため
# 冪等(何度実行しても安全)。`.env`のLBS_*_DB_PASSWORDを変更した場合、ボリュームを
# 再作成しなくても、コンテナ再作成後に本スクリプトを手動で再実行すればMySQL側の
# パスワードを新しい値に同期できる。手順はdocs/SERVICE_SCHEMA_MIGRATION.mdを参照:
#   docker compose up -d mysql
#   docker compose exec mysql bash /docker-entrypoint-initdb.d/01-create-service-schemas.sh
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
    ALTER USER IF EXISTS '${schema}'@'%' IDENTIFIED BY '${password}';
    GRANT ALL PRIVILEGES ON \`${schema}\`.* TO '${schema}'@'%';
EOSQL
  echo "スキーマ ${schema} とユーザー ${schema}@% を作成/同期しました"
done

# platform-serviceのBackupServiceが全サービスのMySQLスキーマを横断してバックアップ/リストアする
# ための専用ユーザー(issue #694、C10-2)。各サービス専用ユーザー(上のループ)は自分のスキーマにしか
# アクセスできないため、バックアップ処理にはこの分離を横断できる認証情報が必要になる。MySQL
# rootクレデンシャルではなく、このスクリプトが把握している既知のスキーマ(上のSCHEMA_PASSWORD_ENV
# の全キー)に限定してALL
# PRIVILEGESを付与する(各サービス専用ユーザーと同じ権限レベルを1ユーザーに集約するだけであり、
# mysqlシステムスキーマや想定外のデータベースへはアクセスできない。#570のスキーマ分離の意図を
# 損なわない範囲での最小権限)。
backup_password="${LBS_BACKUP_DB_PASSWORD:-}"
if [ -z "$backup_password" ]; then
  echo "警告: LBS_BACKUP_DB_PASSWORD が未設定のためlbs_backupユーザーの作成をスキップします" >&2
else
  mysql -u root -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
    CREATE USER IF NOT EXISTS 'lbs_backup'@'%' IDENTIFIED BY '${backup_password}';
    ALTER USER IF EXISTS 'lbs_backup'@'%' IDENTIFIED BY '${backup_password}';
EOSQL
  for schema in "${!SCHEMA_PASSWORD_ENV[@]}"; do
    mysql -u root -p"${MYSQL_ROOT_PASSWORD}" -e \
      "GRANT ALL PRIVILEGES ON \`${schema}\`.* TO 'lbs_backup'@'%';"
  done
  echo "ユーザー lbs_backup@% を作成/同期し、既知の全スキーマへの権限を付与しました"
fi

mysql -u root -p"${MYSQL_ROOT_PASSWORD}" -e "FLUSH PRIVILEGES;"
