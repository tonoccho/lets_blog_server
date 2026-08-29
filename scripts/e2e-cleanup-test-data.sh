#!/bin/bash
# E2E(web/e2e)が投入したテストデータを、サービス別に分割された全MySQLスキーマから
# 横断的に削除する(issue #588)。
#
# サービス分割(#570 / ADR-0004)以降、1つのE2Eフィクスチャ(プロジェクト1件・サイト1件)は
# 複数スキーマにまたがる行を生成する:
#   lbs_project   : projects / sites / static_content / tag_design_settings
#   lbs_content   : posts / custom_tags / custom_tag_templates / project_content_settings
#   lbs_media     : generated_images / diagrams / project_image_settings /
#                   generated_image_sequences
#   lbs_ai        : article_plan_sessions / project_ai_settings
#   lbs_analytics : analytics_credentials
#   lets_blog     : project_users / project_image_settings / user_site_authors
# 各specはafterEach/afterAllでUI経由の後片付けを行うが、テストがフィクスチャ作成の
# 途中で失敗した場合や、UIに削除機能が無いテーブル(生成画像のシーケンス等)には
# 孤児行が残りうる。このスクリプトはそれらをまとめて掃除する。
#
# 対象はE2Eの命名規約に一致する行のみ:
#   projects : slug が e2e-* / test-project-* 、または name が "E2E *"
#   sites    : site_key が e2e*
#   その他   : 上記projects/sitesのidに紐づく行、またはE2E固有のプレフィックスを持つ行
# 実データ(手動で作成したプロジェクト・サイト)には一致しない。
#
# 注意: このスクリプトはDBの行のみを削除する。ManagedWordPressサイトの実体
# (wordpressコンテナ内のファイル・専用DB)はUI/APIからのサイト削除でしか解放されないため、
# まずは各specの後片付け(UI経由の削除)が正常に完了することを前提にすること。
# ここでの削除は「テストが途中で落ちて残った行」を掃除するための最後の手段。
#
# 既定はドライラン(削除件数を表示するだけ)。実際に削除するには --yes を付ける。
#
# 使い方:
#   ./scripts/e2e-cleanup-test-data.sh          # ドライラン(何も削除しない)
#   ./scripts/e2e-cleanup-test-data.sh --yes    # 実際に削除する
#
# web/e2e/global-teardown.ts からは E2E_DB_CLEANUP=1 が指定されたときのみ --yes 付きで呼ばれる。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
ENV_FILE="$REPO_ROOT/.env"

MYSQL_CONTAINER="lbs-mysql"
APPLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --yes)
      APPLY=1
      shift
      ;;
    *)
      echo "エラー: 不明な引数 '$1'" >&2
      exit 1
      ;;
  esac
done

if [ ! -f "$ENV_FILE" ]; then
  echo "エラー: $ENV_FILE が見つかりません" >&2
  exit 1
fi

MYSQL_ROOT_PASSWORD="$(grep -m1 '^MYSQL_ROOT_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"
if [ -z "${MYSQL_ROOT_PASSWORD:-}" ]; then
  echo "エラー: .env の MYSQL_ROOT_PASSWORD が未設定です" >&2
  exit 1
fi

if ! docker inspect "$MYSQL_CONTAINER" >/dev/null 2>&1; then
  echo "エラー: コンテナ ${MYSQL_CONTAINER} が見つかりません" >&2
  exit 1
fi

PROJECT_FILTER="slug LIKE 'e2e-%' OR slug LIKE 'test-project-%' OR name LIKE 'E2E %'"
SITE_FILTER="site_key LIKE 'e2e%'"

PROJECT_IDS="SELECT id FROM lbs_project.e2e_project_ids"
SITE_IDS="SELECT id FROM lbs_project.e2e_site_ids"

# "テーブル|WHERE条件" の一覧。削除順はスキーマ内のFK依存
# (tag_design_settings→projects, static_content→sites, projects→sites)を満たす順に並べている。
TARGETS=(
  "lbs_content.posts|site_id IN (${SITE_IDS})"
  "lbs_content.custom_tags|project_id IN (${PROJECT_IDS}) OR tag_name LIKE 'e2e-%'"
  "lbs_content.custom_tag_templates|project_id IN (${PROJECT_IDS}) OR template_name LIKE 'E2E %'"
  "lbs_content.project_content_settings|project_id IN (${PROJECT_IDS})"
  "lbs_media.generated_images|project_id IN (${PROJECT_IDS}) OR prompt LIKE 'E2E fixture image%'"
  "lbs_media.diagrams|project_id IN (${PROJECT_IDS})"
  "lbs_media.project_image_settings|project_id IN (${PROJECT_IDS})"
  "lbs_media.generated_image_sequences|project_key LIKE 'e2e%'"
  "lbs_ai.article_plan_sessions|project_id IN (${PROJECT_IDS})"
  "lbs_ai.project_ai_settings|project_id IN (${PROJECT_IDS})"
  "lbs_analytics.analytics_credentials|project_id IN (${PROJECT_IDS})"
  "lets_blog.project_users|project_id IN (${PROJECT_IDS})"
  "lets_blog.project_image_settings|project_id IN (${PROJECT_IDS})"
  "lets_blog.user_site_authors|site_id IN (${SITE_IDS})"
  "lbs_project.tag_design_settings|project_id IN (${PROJECT_IDS})"
  "lbs_project.static_content|site_id IN (${SITE_IDS})"
  "lbs_project.projects|id IN (${PROJECT_IDS})"
  "lbs_project.sites|id IN (${SITE_IDS})"
)

# 対象idを一時テーブルへ確定させてから各スキーマを処理する(projects/sitesを先に消すと
# 他スキーマの紐づけ先が分からなくなるため、必ず最初にidを取り出す)。
build_sql() {
  local mode="$1" # count | delete

  cat <<SQL
CREATE TEMPORARY TABLE lbs_project.e2e_project_ids (id BIGINT PRIMARY KEY);
INSERT INTO lbs_project.e2e_project_ids SELECT id FROM lbs_project.projects WHERE ${PROJECT_FILTER};
CREATE TEMPORARY TABLE lbs_project.e2e_site_ids (id BIGINT PRIMARY KEY);
INSERT INTO lbs_project.e2e_site_ids SELECT id FROM lbs_project.sites WHERE ${SITE_FILTER};
SQL

  local entry table where
  for entry in "${TARGETS[@]}"; do
    table="${entry%%|*}"
    where="${entry#*|}"
    if [ "$mode" = "count" ]; then
      echo "SELECT '${table}' AS target, COUNT(*) AS rows_matched FROM ${table} WHERE ${where};"
    else
      echo "DELETE FROM ${table} WHERE ${where};"
      echo "SELECT '${table}' AS target, ROW_COUNT() AS rows_deleted;"
    fi
  done
}

run_sql() {
  docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" \
    mysql -u root --table
}

if [ "$APPLY" -eq 1 ]; then
  echo "E2Eテストデータを全スキーマから削除します"
  build_sql delete | run_sql
  echo "削除が完了しました。"
else
  echo "ドライラン(何も削除しません)。実行するには --yes を付けてください。"
  build_sql count | run_sql
fi
