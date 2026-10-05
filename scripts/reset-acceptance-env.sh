#!/bin/bash
# 受け入れテスト環境を「まっさらな状態」へ戻す(issue #945 / AT-19)。
#
# 受け入れテストは毎回この状態から始める。前のテストが残したデータに依存して
# 通る/落ちるテストを作らないため、また初回セットアップ(ユーザー0人)や
# バックアップ/リストアのようにクリーンな状態を要するシナリオを書けるようにするため。
#
# ■ 安全上の制約(重要)
#
# 対象はローカル開発用 docker compose の固定コンテナ名(lbs-mysql / lbs-keycloak /
# lbs-wordpress / lbs-rabbitmq / lbs-media / lbs-comfyui)と固定レルム名(letsblog)だけ。
# **任意のホスト・DB・レルムを指定するオプションは意図的に持たせていない**
# (scripts/provision-e2e-keycloak-users.sh と同じ設計)。共有/本番環境では実行できない。
#
# Keycloak のユーザー削除は **@letsblog.local ドメインのアカウントに限定**する。
# 現在このレルムに実アカウントは居ないが、将来入っても消さないための実装上の保証である。
# 実アカウントを消したい場合はこのスクリプトではなく Keycloak の管理画面を使うこと。
#
# ■ 何を消すか
#
#   MySQL      : 9スキーマ(lbs_identity/project/content/media/ai/publishing/analytics/
#                platform/log)を drop → create し、各サービスを再起動して Flyway に
#                再作成させる。*_test スキーマ(ホストからの ./gradlew test 用)は触らない。
#   MySQL(WP)  : ManagedWordPress のサイト別DB(wp_*)を drop する。
#   Keycloak   : letsblog レルムの *@letsblog.local ユーザーを削除する。
#   WordPress  : /var/www/html/sites/* を削除する。
#   メディア    : 生成画像の保存領域と ComfyUI の output を空にする。
#   RabbitMQ   : 全キューを purge する(削除ではなく purge。宣言はサービス起動時に行われる)。
#
# ■ 使い方
#
#   ./scripts/reset-acceptance-env.sh            # ドライラン(消す対象を表示するだけ)
#   ./scripts/reset-acceptance-env.sh --yes      # 実行する
#
# 冪等。2回続けて実行しても同じ結果になる。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
ENV_FILE="$REPO_ROOT/.env"

# --- 固定値。ここを引数で差し替えられないことが安全装置そのもの ---
readonly MYSQL_CONTAINER="lbs-mysql"
readonly KEYCLOAK_CONTAINER="lbs-keycloak"
readonly WORDPRESS_CONTAINER="lbs-wordpress"
readonly RABBITMQ_CONTAINER="lbs-rabbitmq"
readonly MEDIA_CONTAINER="lbs-media"
readonly COMFYUI_CONTAINER="lbs-comfyui"
readonly KEYCLOAK_REALM="letsblog"
# compose のプロジェクト名。git worktree(ディレクトリ名が違う)から実行しても
# 共有スタックのコンテナへ restart が効くよう固定する(#1635)。
readonly COMPOSE_PROJECT="lets_blog_server"
# このドメインのアカウントだけを削除対象にする。実アカウントを守るための境界。
readonly SYNTHETIC_EMAIL_DOMAIN="@letsblog.local"
readonly SERVICE_SCHEMAS=(
  lbs_identity lbs_project lbs_content lbs_media lbs_ai
  lbs_publishing lbs_analytics lbs_platform lbs_log
)
# 再起動するサービス(compose のサービス名)。
#
# 前半9つは Flyway を空スキーマから再実行させるため。
#
# gateway と web も必ず含めること。下流サービスだけを再起動すると、gateway が
# **再起動した下流への転送で 401 を返し続ける**(2026-09-01 に実測。gateway から
# identity を直接叩くと 200、同じパスを gateway 経由にすると 401。gateway を
# 再起動すると解消する)。初回セットアップ導線 /api/auth/setup-status は
# identity 側で permitAll なので、これは認可の問題ではなく gateway 側の状態の問題である。
# リセット直後にこれを踏むと「セットアップが必要かどうか判定できない」で全段階が止まる。
# 根本原因は #951 で追う。ここでは gateway を確実に作り直すことで回避する。
readonly RESTART_SERVICES=(
  identity project content media ai publishing analytics platform log-writer
  gateway web
)

APPLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --yes) APPLY=1; shift ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "エラー: 不明な引数 '$1'(このスクリプトは接続先を指定するオプションを持ちません)" >&2; exit 1 ;;
  esac
done

log()  { printf '%s\n' "$*"; }
step() { printf '\n--- %s ---\n' "$*"; }

if [ ! -f "$ENV_FILE" ]; then
  echo "エラー: $ENV_FILE が見つかりません" >&2
  exit 1
fi

env_value() { grep -m1 "^$1=" "$ENV_FILE" | cut -d= -f2- || true; }

MYSQL_ROOT_PASSWORD="$(env_value MYSQL_ROOT_PASSWORD)"
KEYCLOAK_ADMIN_USERNAME="$(env_value KEYCLOAK_ADMIN_USERNAME)"
KEYCLOAK_ADMIN_PASSWORD="$(env_value KEYCLOAK_ADMIN_PASSWORD)"

if [ -z "${MYSQL_ROOT_PASSWORD:-}" ]; then
  echo "エラー: .env の MYSQL_ROOT_PASSWORD が未設定です" >&2
  exit 1
fi
if [ -z "${KEYCLOAK_ADMIN_USERNAME:-}" ] || [ -z "${KEYCLOAK_ADMIN_PASSWORD:-}" ]; then
  echo "エラー: .env の KEYCLOAK_ADMIN_USERNAME / KEYCLOAK_ADMIN_PASSWORD が未設定です" >&2
  exit 1
fi

require_container() {
  if ! docker inspect "$1" >/dev/null 2>&1; then
    echo "エラー: コンテナ $1 が見つかりません(docker compose up -d を先に実行してください)" >&2
    exit 1
  fi
}
require_container "$MYSQL_CONTAINER"
require_container "$KEYCLOAK_CONTAINER"

mysql_q() {
  docker exec -i "$MYSQL_CONTAINER" \
    mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -N -B -e "$1" 2>/dev/null
}
kcadm() { docker exec "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }

# ---------------------------------------------------------------- 調査(ドライランと共通)

WP_DATABASES="$(mysql_q "SHOW DATABASES LIKE 'wp\\_%';" || true)"
WP_SITE_DIRS=""
if docker inspect "$WORDPRESS_CONTAINER" >/dev/null 2>&1; then
  WP_SITE_DIRS="$(docker exec "$WORDPRESS_CONTAINER" sh -c 'ls -1 /var/www/html/sites 2>/dev/null' || true)"
fi

kcadm config credentials \
  --server http://localhost:8080/auth --realm master \
  --user "$KEYCLOAK_ADMIN_USERNAME" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

# レルムの全ユーザーを取り、合成ドメインのものだけを対象にする。
KC_USERS_JSON="$(kcadm get users -r "$KEYCLOAK_REALM" --fields id,username,email 2>/dev/null || echo '[]')"
KC_TARGETS="$(printf '%s' "$KC_USERS_JSON" | python3 -c "
import sys, json
domain = '$SYNTHETIC_EMAIL_DOMAIN'
try:
    users = json.load(sys.stdin)
except Exception:
    users = []
for u in users:
    email = (u.get('email') or u.get('username') or '')
    if email.endswith(domain):
        print(u['id'], email)
")"
KC_PROTECTED="$(printf '%s' "$KC_USERS_JSON" | python3 -c "
import sys, json
domain = '$SYNTHETIC_EMAIL_DOMAIN'
try:
    users = json.load(sys.stdin)
except Exception:
    users = []
for u in users:
    email = (u.get('email') or u.get('username') or '')
    if not email.endswith(domain):
        print(email)
")"

step "削除対象"
log "MySQL スキーマ (drop → create → Flyway で再作成):"
for s in "${SERVICE_SCHEMAS[@]}"; do
  rows="$(mysql_q "SELECT IFNULL(SUM(table_rows),0) FROM information_schema.tables WHERE table_schema='$s';" || echo '?')"
  log "  - $s (概算 ${rows} 行)"
done
log "ManagedWordPress のサイト別DB:"
if [ -n "$WP_DATABASES" ]; then printf '  - %s\n' $WP_DATABASES; else log "  (なし)"; fi
log "WordPress のサイトディレクトリ:"
if [ -n "$WP_SITE_DIRS" ]; then printf '  - /var/www/html/sites/%s\n' $WP_SITE_DIRS; else log "  (なし)"; fi
log "Keycloak (${KEYCLOAK_REALM} レルム) の削除対象ユーザー:"
if [ -n "$KC_TARGETS" ]; then printf '%s\n' "$KC_TARGETS" | awk '{print "  - "$2}'; else log "  (なし)"; fi
if [ -n "$KC_PROTECTED" ]; then
  log "Keycloak の保護対象(${SYNTHETIC_EMAIL_DOMAIN} 以外。削除しない):"
  printf '%s\n' "$KC_PROTECTED" | sed 's/^/  - /'
fi

if [ "$APPLY" -eq 0 ]; then
  step "ドライラン"
  log "何も削除していません。実行するには --yes を付けてください。"
  log "  ./scripts/reset-acceptance-env.sh --yes"
  exit 0
fi

START_TS=$(date +%s)

# ---------------------------------------------------------------- 1. MySQL

step "1/6 MySQL のサービススキーマを作り直します"
for s in "${SERVICE_SCHEMAS[@]}"; do
  mysql_q "DROP DATABASE IF EXISTS \`$s\`; CREATE DATABASE \`$s\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" >/dev/null
  log "  drop+create: $s"
done

step "2/6 ManagedWordPress のサイト別DBを削除します"
if [ -n "$WP_DATABASES" ]; then
  for db in $WP_DATABASES; do
    mysql_q "DROP DATABASE IF EXISTS \`$db\`;" >/dev/null
    log "  drop: $db"
  done
else
  log "  (対象なし)"
fi

# スキーマを作り直すとサービス専用ユーザーの GRANT が消えるため、init スクリプトを再実行する。
# CREATE USER に加えて ALTER USER も行うため冪等(#667)。
log "  サービス専用ユーザーの権限を張り直します"
# DROP DATABASE でスキーマごとの GRANT が失われるため、init スクリプトを再実行する。
# CREATE USER に加えて ALTER USER も行うので冪等(#667)。
init_env_args=(-e "MYSQL_ROOT_PASSWORD=$MYSQL_ROOT_PASSWORD")
while IFS= read -r line; do
  case "$line" in
    LBS_*_DB_PASSWORD=*) init_env_args+=(-e "$line") ;;
  esac
done < "$ENV_FILE"
if docker exec "${init_env_args[@]}" "$MYSQL_CONTAINER" \
     bash /docker-entrypoint-initdb.d/01-create-service-schemas.sh >/dev/null 2>&1; then
  log "  OK: サービス専用ユーザーの権限を張り直しました"
else
  echo "エラー: 01-create-service-schemas.sh の再実行に失敗しました。" >&2
  echo "       権限が無いままだと各サービスの Flyway が起動時に落ちます。" >&2
  exit 1
fi

# ---------------------------------------------------------------- 2. Keycloak

step "3/6 Keycloak の合成アカウントを削除します"
if [ -n "$KC_TARGETS" ]; then
  while read -r id email; do
    [ -z "$id" ] && continue
    kcadm delete "users/$id" -r "$KEYCLOAK_REALM" >/dev/null 2>&1 && log "  delete: $email" \
      || log "  警告: 削除に失敗 $email"
  done <<< "$KC_TARGETS"
else
  log "  (対象なし)"
fi

# ---------------------------------------------------------------- 3. WordPress

step "4/6 WordPress のサイト実体を削除します"
if docker inspect "$WORDPRESS_CONTAINER" >/dev/null 2>&1; then
  if [ -n "$WP_SITE_DIRS" ]; then
    for slug in $WP_SITE_DIRS; do
      docker exec "$WORDPRESS_CONTAINER" rm -rf "/var/www/html/sites/$slug"
      log "  rm -rf: /var/www/html/sites/$slug"
    done
  else
    log "  (対象なし)"
  fi
else
  log "  スキップ: $WORDPRESS_CONTAINER が見つかりません"
fi

# ---------------------------------------------------------------- 4. メディア

step "5/6 生成画像と ComfyUI の output を空にします"
# `docker inspect` はコンテナが Created(compose の gpu プロファイルで未起動の
# lbs-comfyui など)でも成功するため、存在確認では docker exec の可否を決められない。
# 実行中(State.Running == true)であることで判定する(#1306)。
is_running() { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || true)" = "true" ]; }

if is_running "$MEDIA_CONTAINER"; then
  docker exec "$MEDIA_CONTAINER" sh -c 'rm -rf /app/data/generated-images/* 2>/dev/null || true'
  log "  空にしました: ${MEDIA_CONTAINER}:/app/data/generated-images"
else
  log "  スキップ: $MEDIA_CONTAINER は未起動のためスキップします"
fi
if is_running "$COMFYUI_CONTAINER"; then
  docker exec "$COMFYUI_CONTAINER" sh -c 'rm -rf /root/ComfyUI/output/* 2>/dev/null || true'
  log "  空にしました: ${COMFYUI_CONTAINER}:/root/ComfyUI/output"
else
  log "  スキップ: $COMFYUI_CONTAINER は未起動のためスキップします"
fi

# ---------------------------------------------------------------- 5. RabbitMQ

step "6/6 RabbitMQ のキューを purge します"
if docker inspect "$RABBITMQ_CONTAINER" >/dev/null 2>&1; then
  queues="$(docker exec "$RABBITMQ_CONTAINER" rabbitmqctl list_queues -q name 2>/dev/null || true)"
  if [ -n "$queues" ]; then
    for q in $queues; do
      docker exec "$RABBITMQ_CONTAINER" rabbitmqctl purge_queue "$q" >/dev/null 2>&1 || true
    done
    log "  purge: $(printf '%s\n' "$queues" | wc -l) キュー"
  else
    log "  (キューなし)"
  fi
else
  log "  スキップ: $RABBITMQ_CONTAINER が見つかりません"
fi

# ---------------------------------------------------------------- 6. Flyway 再作成

step "サービスを再起動し、Flyway に空スキーマからマイグレーションさせます"
(cd "$REPO_ROOT" && COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" docker compose restart "${RESTART_SERVICES[@]}" >/dev/null 2>&1)
log "  再起動しました: ${RESTART_SERVICES[*]}"
log "  healthy になるまで待ちます"
(cd "$REPO_ROOT" && COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" ./scripts/wait-for-stack-healthy.sh >/dev/null) \
  && log "  OK: 全サービスが healthy です" \
  || { echo "エラー: 再起動後に healthy になりませんでした。docker compose logs を確認してください" >&2; exit 1; }

# healthy とは別に、初回セットアップ導線が gateway 経由で到達できることを確かめる。
# シードも受け入れテストもこの1本から始まるため、ここが 401 のままだと後続が
# 「原因の分からない失敗」として大量に落ちる(上の RESTART_SERVICES のコメント参照)。
log "  初回セットアップ導線の到達性を確認します"
setup_status_code="$(curl -sk -o /dev/null -w '%{http_code}' -m 15 https://localhost/api/auth/setup-status || echo 000)"
if [ "$setup_status_code" != "200" ]; then
  echo "エラー: GET /api/auth/setup-status が ${setup_status_code} を返しました(200 を期待)。" >&2
  echo "       gateway がリセット前の状態を掴んだままの可能性があります(#951)。" >&2
  echo "       docker compose restart gateway を試してから再実行してください。" >&2
  exit 1
fi
log "  OK: GET /api/auth/setup-status → 200"

# ---------------------------------------------------------------- 7. 自己検証

# 「消したつもり」で終わらせない。リセットが成立していないまま受け入れテストを始めると、
# 前のデータに依存した結果が出て、しかもそれが分からない。
step "リセットが成立したことを検証します"

verify_failed=0

# 7-1. 9スキーマに Flyway 管理テーブル以外のデータが残っていないこと。
#
# ただし**マイグレーションが投入するマスタデータは残っていて当然**なので数えない。
# lbs_identity の roles / role_permissions は V2__seed_roles_and_permissions.sql が入れる
# RBACの定義であり(issue #956)、これが空のほうが異常である。
# マスタデータを投入するマイグレーションを足したら、ここにも足すこと。
for s in "${SERVICE_SCHEMAS[@]}"; do
  tables="$(mysql_q "SELECT table_name FROM information_schema.tables
                     WHERE table_schema='$s'
                       AND table_name NOT IN ('flyway_schema_history', 'roles', 'role_permissions');" || true)"
  total=0
  for t in $tables; do
    c="$(mysql_q "SELECT COUNT(*) FROM \`$s\`.\`$t\`;" || echo 0)"
    total=$(( total + c ))
  done
  if [ "$total" -ne 0 ]; then
    echo "  NG: $s に ${total} 行残っています" >&2
    verify_failed=1
  fi
done
[ "$verify_failed" -eq 0 ] && log "  OK: 9スキーマにドメインデータが残っていません"

# 7-2. Keycloak に合成アカウントが残っていないこと。
remaining="$(kcadm get users -r "$KEYCLOAK_REALM" --fields email 2>/dev/null | python3 -c "
import sys, json
domain = '$SYNTHETIC_EMAIL_DOMAIN'
try:
    users = json.load(sys.stdin)
except Exception:
    users = []
print(' '.join(u.get('email','') for u in users if (u.get('email') or '').endswith(domain)))
")"
if [ -n "$remaining" ]; then
  echo "  NG: Keycloak に合成アカウントが残っています: $remaining" >&2
  verify_failed=1
else
  log "  OK: Keycloak に合成アカウントが残っていません"
fi

# 7-3. WordPress のサイト実体が残っていないこと。
if docker inspect "$WORDPRESS_CONTAINER" >/dev/null 2>&1; then
  left="$(docker exec "$WORDPRESS_CONTAINER" sh -c 'ls -1A /var/www/html/sites 2>/dev/null | wc -l' || echo 0)"
  if [ "${left:-0}" -ne 0 ]; then
    echo "  NG: WordPress にサイトが ${left} 件残っています" >&2
    verify_failed=1
  else
    log "  OK: WordPress にサイトが残っていません"
  fi
fi

if [ "$verify_failed" -ne 0 ]; then
  echo "エラー: リセットが成立していません(上の NG を参照)。" >&2
  exit 1
fi

ELAPSED=$(( $(date +%s) - START_TS ))
step "完了"
log "所要時間: ${ELAPSED} 秒"
log "次: ./scripts/seed-acceptance-env.sh でシードを投入してください。"
