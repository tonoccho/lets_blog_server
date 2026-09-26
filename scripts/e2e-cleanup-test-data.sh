#!/bin/bash
# E2E(apps/web/e2e)が投入したテストデータを、サービス別に分割された全MySQLスキーマから
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
#   lbs_identity  : project_users / user_site_authors / users
#                   (users はE2Eが作成した検証用ユーザー。issue #1193)
# 旧スキーマ lets_blog は #786 で lbs_identity へ移されて存在しない。参照すると mysql が
# "Unknown database" で中断し、後続の削除まで届かないため対象に含めない(issue #1193)。
# 各specはafterEach/afterAllでUI経由の後片付けを行うが、テストがフィクスチャ作成の
# 途中で失敗した場合や、UIに削除機能が無いテーブル(生成画像のシーケンス等)には
# 孤児行が残りうる。このスクリプトはそれらをまとめて掃除する。
#
# 対象はE2Eの命名規約に一致する行のみ:
#   projects : slug が e2e-* / test-project-* 、または name が "E2E *"
#   sites    : site_key が e2e*
#   その他   : 上記projects/sitesのidに紐づく行、またはE2E固有のプレフィックスを持つ行
#   users    : email が e2e-*@example.com(lbs_identity.users と、Keycloakの同名ユーザー)
# 実データ(手動で作成したプロジェクト・サイト・ユーザー)には一致しない。
#
# identityユーザーはローカルDBの行に加えてKeycloak側にも実体があるため、DB行と同じ命名規約に
# 一致するKeycloakユーザーも kcadm で削除する(issue #1193)。Keycloakコンテナが無い、または
# .env の KEYCLOAK_ADMIN_USERNAME / KEYCLOAK_ADMIN_PASSWORD が未設定の場合は、Keycloak側の
# 掃除だけを警告付きでスキップし、DB行の削除は続行する。
#
# ManagedWordPressサイト(sites.managed_wordpress = 1)については、DB行を消す前に
# wordpressコンテナ内のプロビジョニングエージェント(POST /deprovision、ポート9000、
# infra/wordpress/provision-agent/index.php)を呼び出して実体(サイトディレクトリと専用DB)も解放する
# (issue #765。従来はDB行しか消せず、e2eの孤児サイトの実体が残り続けていた)。
# エージェントはproject-serviceのサイト削除が呼ぶものと同一で、rm -rf と DROP DATABASE IF EXISTS の
# どちらも冪等なため、UI経由の削除で既に解放済みのサイトに対して再実行しても問題ない。
# wordpressコンテナが起動していない、または .env の WP_PROVISION_TOKEN が未設定の場合は、
# 実体の解放だけを警告付きでスキップし、DB行の削除は続行する。
#
# 既定はドライラン(削除件数を表示するだけ)。実際に削除するには --yes を付ける。
#
# 使い方:
#   ./scripts/e2e-cleanup-test-data.sh          # ドライラン(何も削除しない)
#   ./scripts/e2e-cleanup-test-data.sh --yes    # 実際に削除する
#
# apps/web/e2e/global-teardown.ts からは E2E_DB_CLEANUP=1 が指定されたときのみ --yes 付きで呼ばれる。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
ENV_FILE="$REPO_ROOT/.env"

MYSQL_CONTAINER="lbs-mysql"
WORDPRESS_CONTAINER="lbs-wordpress"
KEYCLOAK_CONTAINER="lbs-keycloak"
KEYCLOAK_REALM="letsblog"
# E2Eが作るidentityユーザーのメールアドレス(SQLのLIKEと、Keycloak側の正規表現で同じ規約を表す)。
E2E_USER_EMAIL_LIKE="e2e-%@example.com"
E2E_USER_EMAIL_REGEX='^e2e-.*@example\.com$'
# プロビジョニングエージェントの待ち受け先(infra/wordpress/start.sh。コンテナ内からのみ叩く)。
PROVISION_AGENT_URL="http://127.0.0.1:9000"
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

# 実体の解放(/deprovision)にのみ使う。未設定でもDB行の削除は行えるため、ここでは中断しない。
WP_PROVISION_TOKEN="$(grep -m1 '^WP_PROVISION_TOKEN=' "$ENV_FILE" | cut -d= -f2- || true)"

# Keycloakユーザーの掃除にのみ使う。未設定でもDB行の削除は行えるため、ここでは中断しない。
KEYCLOAK_ADMIN_USERNAME="$(grep -m1 '^KEYCLOAK_ADMIN_USERNAME=' "$ENV_FILE" | cut -d= -f2- || true)"
KEYCLOAK_ADMIN_PASSWORD="$(grep -m1 '^KEYCLOAK_ADMIN_PASSWORD=' "$ENV_FILE" | cut -d= -f2- || true)"

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
  "lbs_identity.project_users|project_id IN (${PROJECT_IDS})"
  "lbs_identity.user_site_authors|site_id IN (${SITE_IDS})"
  "lbs_identity.users|email LIKE '${E2E_USER_EMAIL_LIKE}'"
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

# 対象E2Eサイトのうち、実体(wordpressコンテナ内のディレクトリと専用DB)を持つものを
# "wp_slug<TAB>wp_db_name" の形式で列挙する。失敗時は非0で返る(pipefailによりdocker execの
# 失敗がそのままこの関数の終了ステータスになる)。呼び出し側は必ず終了ステータスを見ること。
list_managed_sites() {
  echo "SELECT wp_slug, wp_db_name FROM lbs_project.sites
        WHERE (${SITE_FILTER}) AND managed_wordpress = 1
          AND wp_slug IS NOT NULL AND wp_slug <> ''
          AND wp_db_name IS NOT NULL AND wp_db_name <> '';" \
    | docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" \
        mysql -u root --batch --skip-column-names
}

# ManagedWordPressの実体を解放する(issue #765)。DB行を消す前に呼ぶこと
# (消した後ではwp_slug/wp_db_nameが分からなくなる)。
deprovision_managed_sites() {
  local mode="$1" # count | delete
  local slug db_name rows matched=0 failed=0

  if ! docker inspect "$WORDPRESS_CONTAINER" >/dev/null 2>&1; then
    echo "警告: コンテナ ${WORDPRESS_CONTAINER} が見つからないため、ManagedWordPressの実体は解放しません" >&2
    return 0
  fi
  if [ -z "${WP_PROVISION_TOKEN:-}" ]; then
    echo "警告: .env の WP_PROVISION_TOKEN が未設定のため、ManagedWordPressの実体は解放しません" >&2
    return 0
  fi

  # プロセス置換(done < <(...))は供給側コマンドの終了ステータスをシェルが一切見ない
  # (set -euo pipefailの対象外)。一覧取得が失敗しても0件と区別できず、警告も出ないまま
  # DB行の削除へ進んでしまうため、コマンド置換で受けて終了ステータスを必ず確認する。
  if ! rows="$(list_managed_sites)"; then
    echo "エラー: ManagedWordPressサイトの一覧取得に失敗しました" >&2
    return 1
  fi

  while IFS=$'\t' read -r slug db_name; do
    [ -z "$slug" ] && continue
    # プロビジョニングエージェント側のバリデーションと同じ文字種に限定する
    # (JSONへ素で埋め込むため、想定外の値はここで弾く)。
    if ! [[ "$slug" =~ ^[a-z0-9-]+$ ]] || ! [[ "$db_name" =~ ^[a-z0-9_-]+$ ]]; then
      echo "警告: 不正な値のためスキップします (slug='${slug}', dbName='${db_name}')" >&2
      continue
    fi
    matched=$((matched + 1))

    if [ "$mode" = "count" ]; then
      echo "  - ${slug} (DB: ${db_name})"
      continue
    fi

    # --max-time: 1件でハングしてもglobal-teardown.ts側の実行時間上限(300秒)を
    # 使い切らないように上限を設ける(UI経由の削除でも60秒で完了する処理のため十分)。
    if docker exec "$WORDPRESS_CONTAINER" curl -fsS --max-time 120 \
        -X POST "${PROVISION_AGENT_URL}/deprovision" \
        -H "X-Provision-Token: ${WP_PROVISION_TOKEN}" \
        -H 'Content-Type: application/json' \
        -d "{\"slug\":\"${slug}\",\"dbName\":\"${db_name}\"}" >/dev/null; then
      echo "  - ${slug} の実体を解放しました (DB: ${db_name})"
    else
      failed=$((failed + 1))
      # 直後のDB行削除でwp_slug/wp_db_nameは失われるため、手動で解放し直せるだけの情報を
      # ここでログに残す(issue #765)。
      echo "  ! ${slug} の実体の解放に失敗しました (DB: ${db_name})。手動で解放するには:" >&2
      echo "      docker exec ${WORDPRESS_CONTAINER} curl -fsS -X POST ${PROVISION_AGENT_URL}/deprovision \\" >&2
      echo "        -H \"X-Provision-Token: \$(grep '^WP_PROVISION_TOKEN=' .env | cut -d= -f2-)\" \\" >&2
      echo "        -d '{\"slug\":\"${slug}\",\"dbName\":\"${db_name}\"}'" >&2
    fi
  done <<< "$rows"

  if [ "$matched" -eq 0 ]; then
    echo "  (対象なし)"
  fi
  if [ "$failed" -gt 0 ]; then
    echo "警告: ${failed}件のManagedWordPress実体を解放できませんでした(DB行の削除は続行します)" >&2
  fi
}

kcadm() { docker exec "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }

# E2E命名規約に一致するKeycloakユーザーを掃除する(issue #1193)。実行できない場合は警告を出して
# 戻る(DB行の削除は妨げない)。
cleanup_keycloak_users() {
  local mode="$1" # count | delete
  local users_json targets id email matched=0 failed=0

  if ! docker inspect "$KEYCLOAK_CONTAINER" >/dev/null 2>&1; then
    echo "警告: コンテナ ${KEYCLOAK_CONTAINER} が見つからないため、Keycloakのユーザーは掃除しません" >&2
    return 0
  fi
  if [ -z "${KEYCLOAK_ADMIN_USERNAME:-}" ] || [ -z "${KEYCLOAK_ADMIN_PASSWORD:-}" ]; then
    echo "警告: .env の KEYCLOAK_ADMIN_USERNAME / KEYCLOAK_ADMIN_PASSWORD が未設定のため、Keycloakのユーザーは掃除しません" >&2
    return 0
  fi

  # 一覧の既定上限(100件)で孤児を取りこぼさないよう、search で絞り込み max を引き上げる。
  # 実際の対象判定は下の正規表現が行う(search は部分一致で、絞り込みにすぎない)。
  if ! kcadm config credentials --server http://localhost:8080/auth --realm master \
      --user "$KEYCLOAK_ADMIN_USERNAME" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null 2>&1 \
     || ! users_json="$(kcadm get users -r "$KEYCLOAK_REALM" -q search=e2e- -q max=10000 --fields id,username,email 2>/dev/null)"; then
    echo "警告: Keycloakのユーザー一覧を取得できないため、Keycloakのユーザーは掃除しません" >&2
    return 0
  fi

  targets="$(printf '%s' "$users_json" | E2E_REGEX="$E2E_USER_EMAIL_REGEX" python3 -c "
import sys, json, os, re
pattern = re.compile(os.environ['E2E_REGEX'])
try:
    users = json.load(sys.stdin)
except Exception:
    users = []
for u in users:
    email = u.get('email') or u.get('username') or ''
    if pattern.match(email):
        print(u['id'], email)
")"

  while read -r id email; do
    [ -z "$id" ] && continue
    matched=$((matched + 1))
    if [ "$mode" = "count" ]; then
      echo "  - ${email}"
    elif kcadm delete "users/${id}" -r "$KEYCLOAK_REALM" >/dev/null 2>&1; then
      echo "  - ${email} を削除しました"
    else
      failed=$((failed + 1))
      echo "  ! ${email} の削除に失敗しました (id: ${id})" >&2
    fi
  done <<< "$targets"

  if [ "$matched" -eq 0 ]; then
    echo "  (対象なし)"
  fi
  if [ "$failed" -gt 0 ]; then
    echo "警告: ${failed}件のKeycloakユーザーを削除できませんでした(DB行の削除は続行します)" >&2
  fi
}

if [ "$APPLY" -eq 1 ]; then
  echo "ManagedWordPressの実体を解放します"
  # 解放対象を確定できなかった場合はDB行を消さずに中断する。行を消すとwp_slug/wp_db_nameが
  # 失われ、実体を手動で解放する手段も無くなるため(issue #765)。
  if ! deprovision_managed_sites delete; then
    echo "エラー: 解放対象を確定できなかったため、DB行の削除は行いません。" >&2
    echo "       原因(mysqlへの接続等)を解消してから再実行してください。" >&2
    exit 1
  fi
  echo "E2Eが作成したKeycloakユーザーを削除します"
  cleanup_keycloak_users delete
  echo "E2Eテストデータを全スキーマから削除します"
  build_sql delete | run_sql
  echo "削除が完了しました。"
else
  echo "ドライラン(何も削除しません)。実行するには --yes を付けてください。"
  echo "解放対象のManagedWordPress実体:"
  # ドライランでは何も削除しないため、一覧取得に失敗しても(関数が警告を出した上で)
  # DB行の件数表示までは続ける。
  deprovision_managed_sites count || true
  echo "削除対象のKeycloakユーザー:"
  cleanup_keycloak_users count
  build_sql count | run_sql
fi
