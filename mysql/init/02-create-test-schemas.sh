#!/bin/bash
# content/media/ai/analytics/platformサービスの結合テスト用スキーマ作成(#664、platform分は#693)。
# 各サービスのapplication-test.yml(ADR-0006: Testcontainersは使わず実MySQLに接続する方式)が
# 参照するlbs_{content,media,ai,analytics,platform}_testスキーマを、01-create-service-schemas.shと
# 同じくMySQLコンテナの初回起動時(docker-entrypoint-initdb.d、データボリュームが空の場合のみ
# 実行される)に作成する。既存のtest_user(scripts/setup-test-db.shがlets_blog_testに対して作成する
# ユーザーと同じ資格情報)に、このスクリプトが作成する各スキーマへの同レベルの権限
# (ALL PRIVILEGES)を付与する。
#
# 注意: 既存のMySQLデータボリュームが既にある環境では、コンテナを再作成しても
# このスクリプトは実行されない(MySQL公式イメージの仕様)。適用するにはボリュームの
# 再作成が必要(01-create-service-schemas.shと同様、docs/adr/0004-schema-per-service.md,
# docs/MIGRATION_TESTING.md 参照)。
set -euo pipefail

TEST_DB_USER="test_user"
TEST_DB_PASSWORD="test_pass"

TEST_SCHEMAS=(
  lbs_content_test
  lbs_media_test
  lbs_ai_test
  lbs_analytics_test
  lbs_platform_test
)

mysql -u root -p"${MYSQL_ROOT_PASSWORD}" -e \
  "CREATE USER IF NOT EXISTS '${TEST_DB_USER}'@'%' IDENTIFIED BY '${TEST_DB_PASSWORD}';"

for schema in "${TEST_SCHEMAS[@]}"; do
  mysql -u root -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
    CREATE DATABASE IF NOT EXISTS \`${schema}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    GRANT ALL PRIVILEGES ON \`${schema}\`.* TO '${TEST_DB_USER}'@'%';
EOSQL
  echo "スキーマ ${schema} を作成し ${TEST_DB_USER}@% に権限を付与しました"
done

mysql -u root -p"${MYSQL_ROOT_PASSWORD}" -e "FLUSH PRIVILEGES;"
