#!/bin/bash
# 受け入れテスト環境を**ゼロから構築し直す**(issue #965)。
#
# 利用者の方針(2026-09-01)は次の3つで、実行順序は「全撤去 → ゼロから構築 → テスト」である。
#
#   1. システムを0から構築してスタートする
#   2. テストを行う
#   3. テストのために作成した一切のものを削除する
#
# 3 はテスト終了後の後片付けではなく、**次回実行の最初のステップ**として行う。
# 終了時に何も消さないので、失敗の調査は実行後のスタックに対してそのまま行える(#945 の判断)。
#
# ■ scripts/reset-acceptance-env.sh との違い
#
#   reset-acceptance-env.sh : データ層だけを初期化する(既存コンテナへの docker exec)。
#                             速いが、コンテナ・イメージ・ボリュームは実行をまたいで残る。
#                             低リスクな経路として残してある。ドメイン限定の安全装置もそのまま。
#   このスクリプト          : ボリュームごと破棄し、今のソースツリーだけを入力に組み上げ直す。
#                             列挙漏れが起きえないので「一切を削除する」が構造的に成立する。
#
# ■ 手順
#
#   0. プローブ設置 — 直前のスタックが起動していれば at-wipe-probe-<epoch> を3つ置く
#   1. 全撤去      — docker compose down --remove-orphans + 破棄対象ボリュームの削除
#   2. 構築        — docker compose up -d --build(ソースからビルド)
#   3. 健全性待ち  — scripts/wait-for-stack-healthy.sh を再利用する(重複実装しない)
#   4. 検証        — 破棄と構築が成立したことを確かめ、崩れていれば非0で終了する
#
# ■ 安全装置(1)— 接続先を引数で差し替えられない
#
# compose プロジェクト名(lets_blog_server)・ボリューム名・コンテナ名・レルム名は
# すべて readonly で固定してある。**任意のホスト・プロジェクト・ボリュームを指定する
# オプションは意図的に持たせていない**(scripts/reset-acceptance-env.sh、
# scripts/provision-e2e-keycloak-users.sh と同じ設計)。共有/本番環境では実行できない。
# --yes なしはドライランで、破棄するボリューム / 保全するボリューム / これから作るプローブ
# の3つを表示し、何も変更しない。
#
# ■ 安全装置(2)— この環境のレルムに、失って困るアカウントを置かない
#
# データ層リセット経路は「@letsblog.local のアカウントだけを消す」というドメイン限定で
# 実アカウントを守っている。ゼロ構築経路には**この安全装置を原理的に適用できない** —
# keycloak_postgres ごと破棄するので、どのアカウントを残すかを選ぶ余地が無いからである。
# 代わりに前提を明文化する: **受け入れテスト環境の letsblog レルムには、失って困る
# アカウントを置かない。** テストが使うアカウントはすべてテスト自身が作る
# (at-setup が最初の管理者を、at-seed が e2e-*@letsblog.local を作る)。
# 恒久的に保持したい実アカウントが必要になったら、受け入れテスト環境ではない別環境で扱う。
# このスクリプトが**自分で作る**のは at-wipe-probe-<epoch>@letsblog.local だけであり、
# それ以外のアカウントを名指しで作成・削除する処理は持たない。
#
# ■ ウォッシュアウト・プローブ(#965 §7)
#
# 「消えていること」だけを見る検証は、**そもそも何も入っていなかった場合と区別できない**。
# そこで撤去の直前に自分で3つのダミーを置き、構築後にそれが消えたことで破棄の成立を示す。
# 消すのは docker compose の撤去とボリューム破棄であって、**正常系では**個別削除の処理を
# 実行しない(ボリューム破棄で消えること自体が検証対象である)。
# 例外は中断時の後片付け(#1245)だけ: 手順0で最初のプローブを作り始めてから手順1の
# ボリューム破棄が完了するまでの間に中断・異常終了(SIGINT / SIGTERM / 非0終了)したら、
# **その実行が実際に作ったプローブだけ**を消す。ボリューム破棄の完了後は後片付けを解除する。
# SIGKILL・電源断はスクリプトが処理を差し挟めないので対象外。
#
# ■ 使い方
#
#   ./scripts/rebuild-acceptance-env.sh              # ドライラン(何も変更しない)
#   ./scripts/rebuild-acceptance-env.sh --yes        # 実行する
#   ./scripts/rebuild-acceptance-env.sh --yes --no-cache   # イメージをキャッシュ無しで作り直す
#
# 環境変数:
#   ACCEPTANCE_HEALTH_TIMEOUT   健全性待ちの上限秒数(既定 900)。待つ時間だけを変えられる。
#
# 冪等。2回続けて実行しても同じ結果になる。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$REPO_ROOT/.env"

# --- 固定値。ここを引数で差し替えられないことが安全装置そのもの ---
readonly COMPOSE_PROJECT="lets_blog_server"
readonly VOLUME_PREFIX="lets_blog_server_"

# 破棄するボリューム。docker-compose.yml の volumes: が宣言するもののうち、
# 保全対象を除いた全部。列挙漏れは test_rebuild_acceptance_env.py が検査する。
#
# keycloak_postgres を破棄するのは意図的である。レルム定義が毎回
# infra/keycloak/realm-export.json から再インポートされ、Git 管理された設定と実環境が
# 一致することが保証される(--import-realm は既存レルムがあると再インポートしないため、
# 残したままでは誰もこの一致を検証していない)。
readonly DESTROY_VOLUMES=(
  mysql_data
  rabbitmq_data
  keycloak_postgres
  penpot_postgres
  penpot_assets
  comfyui_output
  wordpress_sites
  bulk_upload_files
  generated_images
  avatar_images
)

# 保全するボリューム。
#
#   comfyui_models — 画像生成のモデル重み。再取得に長時間かかり、テスト対象の状態でもない。
#                    ここだけは実行をまたいで保持する(CreatedAt が変わらないことを検証する)。
#   ollama_models  — Ollama のモデル重み(issue #1086)。約4.7GBあり、再取得に長時間かかる。
#                    受け入れテストの LLM 呼び出しは llm-stub が受けるため(#1086 R7)、
#                    テスト対象の状態でもない。comfyui_models と同様、実行をまたいで
#                    保持する(CreatedAt が変わらないことを検証する)。
#
# mysql_data 内の *_test スキーマ(ホストからの ./gradlew test 用)は mysql_data ごと
# 巻き添えで消えるが、構築時に infra/mysql/init/02-create-test-schemas.sh が本来の経路で
# 作り直す。作り直されたことは手順4で確認する。
readonly PRESERVE_VOLUMES=(comfyui_models ollama_models)

readonly MYSQL_CONTAINER="lbs-mysql"
readonly KEYCLOAK_CONTAINER="lbs-keycloak"
readonly WORDPRESS_CONTAINER="lbs-wordpress"
readonly REVERSE_PROXY_CONTAINER="lbs-reverse-proxy"
readonly KEYCLOAK_REALM="letsblog"
readonly BASE_URL="https://localhost"

# レルムに残っていてよい唯一のユーザー。realm-export.json が持つサービスアカウントで、
# エンドユーザーではない。
readonly ALLOWED_KEYCLOAK_USER="service-account-letsblog-services"

readonly SERVICE_SCHEMAS=(
  lbs_identity lbs_project lbs_content lbs_media lbs_ai
  lbs_publishing lbs_analytics lbs_platform lbs_log
)

# ホストに残る前回実行の生成物。撤去と同時に消す。
# .features-gen は bddgen が毎回作り直すものであり、かつ Playwright は globalSetup の
# **あとに**テストファイルを読み込むため、ここで消すと実行中のテストが消える。
# apps/web/package.json の test:at:clean が bddgen の直前に消している。
readonly HOST_ARTIFACTS=(
  "apps/web/test-results"
  "apps/web/playwright-report"
)

# GPU が無いホストでは起動できないサービス。起動失敗を致命傷にしない。
# 受け入れテストは comfyui に依存しない(画像生成は image-stub が受ける)。
readonly OPTIONAL_SERVICES=(comfyui)

# 健全性待ちの上限(秒)。空ボリュームからの起動は Keycloak のレルムインポートと
# 9サービス分の Flyway を含むため、既存スタックの再起動より長くかかる。
# **縮められるのは待つ時間だけ**で、接続先・プロジェクト・ボリュームは差し替えられない。
readonly HEALTH_TIMEOUT_SECONDS="${ACCEPTANCE_HEALTH_TIMEOUT:-900}"

APPLY=0
NO_CACHE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --yes) APPLY=1; shift ;;
    --no-cache) NO_CACHE=1; shift ;;
    -h|--help) sed -n '2,66p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)
      echo "エラー: 不明な引数 '$1'(このスクリプトは接続先・プロジェクト・ボリュームを指定するオプションを持ちません)" >&2
      exit 1
      ;;
  esac
done

log()  { printf '%s\n' "$*"; }
step() { printf '\n--- %s ---\n' "$*"; }

if [ ! -f "$ENV_FILE" ]; then
  echo "エラー: $ENV_FILE が見つかりません(cp .env.example .env で作成してください)" >&2
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

cd "$REPO_ROOT"

# ---------------------------------------------------------------- 補助

container_running() { docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null | grep -q true; }
volume_created_at() { docker volume inspect --format '{{.CreatedAt}}' "$1" 2>/dev/null || true; }
to_epoch() { date -d "$1" +%s 2>/dev/null || echo 0; }
# ボリューム破棄が失敗したとき、まだ参照しているコンテナ名を診断に添えるため(#1293)。
# -a を付けるのは、Created/Exited のまま残ったコンテナ(running していない)も
# ボリュームを握ったままのことがあるため。
referencing_containers() {
  docker ps -a --filter "volume=$1" --format '{{.Names}}' 2>/dev/null | tr '\n' ' ' | sed 's/ *$//'
}

mysql_q() {
  docker exec -i "$MYSQL_CONTAINER" \
    mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -N -B -e "$1" 2>/dev/null
}
kcadm() { docker exec "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }
kcadm_login() {
  kcadm config credentials --server http://localhost:8080/auth --realm master \
    --user "$KEYCLOAK_ADMIN_USERNAME" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null 2>&1
}

# ホストの 80/443 を他プロセスが握っている構成(#1038)では、reverse-proxy はポートを
# 公開せず、共有プロキシ(infra-proxy)が localhost を中継する。この構成を撤去前に判定し、
# 構築時に docker-compose.shared-host.yml を重ねる。判定しないまま素の compose で起動すると
# ポート公開に失敗し、reverse-proxy が**どのネットワークにも所属しないまま running** になる。
#
# 自分自身の reverse-proxy コンテナの状態には依存しない(#1065)。直前のスタックが
# 完全停止している(先行実行の撤去が途中で失敗した直後の再実行、または真に最初の1回)と
# container_running が常に偽になり、下の inspect 判定だけでは検知できない。
# ホストの80/443番を他コンテナが公開しているかどうかを名前を問わず調べることで、
# 自スタックの起動有無に依らず判定する。
other_container_holds_host_port() {
  docker ps --format '{{.Names}}\t{{.Ports}}' 2>/dev/null \
    | awk -F'\t' -v me="$REVERSE_PROXY_CONTAINER" '$1 != me' \
    | grep -Eq ':(80|443)->'
}

SHARED_HOST=0
if container_running "$REVERSE_PROXY_CONTAINER"; then
  bindings="$(docker inspect "$REVERSE_PROXY_CONTAINER" --format '{{len .HostConfig.PortBindings}}' 2>/dev/null || echo 0)"
  [ "${bindings:-0}" = "0" ] && SHARED_HOST=1
fi
if [ "$SHARED_HOST" -eq 0 ] && other_container_holds_host_port; then
  SHARED_HOST=1
fi

COMPOSE_ARGS=(-p "$COMPOSE_PROJECT" -f "$REPO_ROOT/docker-compose.yml" -f "$REPO_ROOT/docker-compose.e2e-stubs.yml")
if [ "$SHARED_HOST" -eq 1 ]; then
  COMPOSE_ARGS+=(-f "$REPO_ROOT/docker-compose.shared-host.yml")
fi
compose() { docker compose "${COMPOSE_ARGS[@]}" "$@"; }

# ---------------------------------------------------------------- 調査(ドライランと共通)

PROBE_ID="$(date +%s)"
readonly PROBE_KC_USER="at-wipe-probe-${PROBE_ID}@letsblog.local"
readonly PROBE_MYSQL_DB="at_wipe_probe_${PROBE_ID}"
readonly PROBE_WP_DIR="/var/www/html/sites/at-wipe-probe-${PROBE_ID}"

STACK_UP=1
for c in "$MYSQL_CONTAINER" "$KEYCLOAK_CONTAINER" "$WORDPRESS_CONTAINER"; do
  container_running "$c" || STACK_UP=0
done

step "破棄するボリューム(docker compose の撤去と volume rm で消える)"
for v in "${DESTROY_VOLUMES[@]}"; do
  created="$(volume_created_at "${VOLUME_PREFIX}${v}")"
  log "  - ${VOLUME_PREFIX}${v}${created:+ (CreatedAt ${created})}"
done

step "保全するボリューム(削除しない)"
for v in "${PRESERVE_VOLUMES[@]}"; do
  created="$(volume_created_at "${VOLUME_PREFIX}${v}")"
  log "  - ${VOLUME_PREFIX}${v}${created:+ (CreatedAt ${created})}  ← モデル重み。再取得に長時間かかる"
done

step "作成するプローブ(撤去が成立したことの証拠)"
if [ "$STACK_UP" -eq 1 ]; then
  log "  - Keycloak ユーザー ${PROBE_KC_USER}          (keycloak_postgres に乗る)"
  log "  - MySQL データベース ${PROBE_MYSQL_DB}        (mysql_data に乗る)"
  log "  - WordPress ディレクトリ ${PROBE_WP_DIR}      (wordpress_sites に乗る)"
else
  log "  プローブ省略(直前のスタックが起動していないため)"
  log "  この場合はボリュームの CreatedAt 検査だけで破棄を判定する。"
fi

step "撤去コマンドの対象範囲(#1293)"
log "  docker compose --profile '*' down --remove-orphans: docker-compose.yml が宣言する"
log "  全プロファイル(comfyui の gpu 等)のコンテナも撤去の対象に含めます。"
log "  プロファイルを指定しないと、Created/Exited のまま残ったコンテナが"
log "  それの乗るボリューム(comfyui_output 等)の破棄をブロックし、途中で止まります。"

if [ "$SHARED_HOST" -eq 1 ]; then
  log ""
  log "共有ホスト構成(#1038)を検出しました。docker-compose.shared-host.yml を重ね、"
  log "構築後に scripts/setup-shared-host-proxy.sh を再適用します。"
fi

if [ "$APPLY" -eq 0 ]; then
  step "ドライラン"
  log "何も変更していません(ボリュームは破棄せず、プローブも作っていません)。"
  log "実行するには --yes を付けてください。"
  log "  ./scripts/rebuild-acceptance-env.sh --yes"
  exit 0
fi

# 実行中の lets_blog_server compose プロジェクトが、このスクリプトを実行している
# 作業ツリーとは別の作業ツリーから作られている場合は、撤去(1/5)を始める前に止める(#1202)。
# 無人ループを複数worktreeで並列に走らせているとき、このまま進むと共有スタックを
# 別ワーカーから黙って乗っ取ってしまう。プロダクションコード差分の有無は問わない —
# 再構築という操作そのものが、相手の足元のスタックを作り替えるため。
# 唯一の迂回路は AT_WORKTREE_CHECK_BYPASS=1(scripts/check-worktree-match.py 側で定義)。
if ! python3 "$SCRIPT_DIR/check-worktree-match.py" rebuild; then
  exit 1
fi

START_TS=$(date +%s)
# 保全対象ごとの CreatedAt(実行前)。手順4-1b でまとめて「変わっていないこと」を検証する。
declare -A PRESERVE_VOLUMES_BEFORE
for v in "${PRESERVE_VOLUMES[@]}"; do
  PRESERVE_VOLUMES_BEFORE["$v"]="$(volume_created_at "${VOLUME_PREFIX}${v}")"
done

# ---------------------------------------------------------------- 中断時の後片付け(#1245)
#
# 手順0で最初のプローブを作り始めてから、手順1のボリューム破棄が完了するまでの間だけ
# 有効にする。作成に成功したものだけを PROBE_*_PLACED で覚え、その分だけを消す。
# 破棄完了後は disarm_probe_cleanup で解除する(正常系ではプローブを個別削除しない。#965 §7)。
PROBE_CLEANUP_ARMED=0
PROBE_KC_PLACED=0
PROBE_DB_PLACED=0
PROBE_WP_PLACED=0

cleanup_probes() {
  local rc=$?
  trap - EXIT INT TERM
  [ "$PROBE_CLEANUP_ARMED" -eq 1 ] || exit "$rc"
  PROBE_CLEANUP_ARMED=0
  set +e
  [ "$rc" -ne 0 ] || rc=1
  echo "" >&2
  echo "--- 中断: この実行が置いたプローブの後片付け ---" >&2
  local left=()
  local kc_id
  if [ "$PROBE_KC_PLACED" -eq 1 ]; then
    kc_id="$(kcadm get users -r "$KEYCLOAK_REALM" -q "username=${PROBE_KC_USER}" --fields id --format csv --noquotes 2>/dev/null | head -n1)"
    if [ -n "$kc_id" ] && kcadm delete "users/${kc_id}" -r "$KEYCLOAK_REALM" >/dev/null 2>&1; then
      echo "  後片付け: Keycloak ユーザー ${PROBE_KC_USER} を削除しました" >&2
    else
      left+=("kc")
    fi
  fi
  if [ "$PROBE_DB_PLACED" -eq 1 ]; then
    if mysql_q "DROP DATABASE IF EXISTS \`${PROBE_MYSQL_DB}\`;" >/dev/null; then
      echo "  後片付け: MySQL データベース ${PROBE_MYSQL_DB} を削除しました" >&2
    else
      left+=("db")
    fi
  fi
  if [ "$PROBE_WP_PLACED" -eq 1 ]; then
    if docker exec "$WORDPRESS_CONTAINER" sh -c "rm -rf ${PROBE_WP_DIR}" >/dev/null 2>&1; then
      echo "  後片付け: WordPress ディレクトリ ${PROBE_WP_DIR} を削除しました" >&2
    else
      left+=("wp")
    fi
  fi
  if [ "${#left[@]}" -gt 0 ]; then
    echo "エラー: 後片付けに失敗しました。次のプローブが残っています。手で消してください:" >&2
    local k
    for k in "${left[@]}"; do
      case "$k" in
        kc)
          echo "  残っています: Keycloak ユーザー ${PROBE_KC_USER}" >&2
          echo "    docker exec ${KEYCLOAK_CONTAINER} /opt/keycloak/bin/kcadm.sh delete users/<id> -r ${KEYCLOAK_REALM}   # <id> は kcadm.sh get users -r ${KEYCLOAK_REALM} -q username=${PROBE_KC_USER} --fields id で調べる" >&2 ;;
        db)
          echo "  残っています: MySQL データベース ${PROBE_MYSQL_DB}" >&2
          echo "    docker exec -i ${MYSQL_CONTAINER} mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e \"DROP DATABASE \\\`${PROBE_MYSQL_DB}\\\`;\"" >&2 ;;
        wp)
          echo "  残っています: WordPress ディレクトリ ${PROBE_WP_DIR}" >&2
          echo "    docker exec ${WORDPRESS_CONTAINER} sh -c 'rm -rf ${PROBE_WP_DIR}'" >&2 ;;
      esac
    done
  fi
  exit "$rc"
}
disarm_probe_cleanup() {
  PROBE_CLEANUP_ARMED=0
  trap - EXIT INT TERM
}
trap cleanup_probes EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---------------------------------------------------------------- 0. プローブ設置

step "0/5 ウォッシュアウト・プローブを設置します"
PROBES_PLACED=0
if [ "$STACK_UP" -eq 1 ]; then
  kcadm_login || { echo "エラー: Keycloak 管理 CLI にログインできません" >&2; exit 1; }
  PROBE_CLEANUP_ARMED=1
  kcadm create users -r "$KEYCLOAK_REALM" \
    -s "username=${PROBE_KC_USER}" -s "email=${PROBE_KC_USER}" -s enabled=true >/dev/null 2>&1 \
    || { echo "エラー: プローブの Keycloak ユーザーを作成できませんでした" >&2; exit 1; }
  PROBE_KC_PLACED=1
  log "  作成: Keycloak ユーザー ${PROBE_KC_USER}"

  mysql_q "CREATE DATABASE IF NOT EXISTS \`${PROBE_MYSQL_DB}\`;" >/dev/null \
    || { echo "エラー: プローブの MySQL データベースを作成できませんでした" >&2; exit 1; }
  PROBE_DB_PLACED=1
  log "  作成: MySQL データベース ${PROBE_MYSQL_DB}"

  docker exec "$WORDPRESS_CONTAINER" sh -c "mkdir -p ${PROBE_WP_DIR}" \
    || { echo "エラー: プローブの WordPress ディレクトリを作成できませんでした" >&2; exit 1; }
  PROBE_WP_PLACED=1
  log "  作成: WordPress ディレクトリ ${PROBE_WP_DIR}"

  # 作成直後の存在確認。ここを省くと、後の「消えている」が
  # 「そもそも作れていなかった」と区別できない(現行リセットの自己検証が持つ弱点)。
  placed_ok=1
  # `kcadm get users | grep -q ...` のまま書くと、grep -q は最初の一致を
  # 見つけた瞬間に自分の標準入力(パイプの読み側)を閉じて終了する。このとき
  # kcadm がまだ出力を書き込み中だと(既存ユーザー数が多く、プローブのユーザー名が
  # アルファベット順で中間に来ると起きやすい)、書き込み中の kcadm は読み手を
  # 失って SIGPIPE を受け、終了コード141で死ぬ。`set -o pipefail` が有効な
  # このスクリプトでは、パイプ全体の終了コードがその141(非0)になり、
  # grep 自身は一致していた(本来は成功=0)にもかかわらず `|| placed_ok=0` に
  # 落ちて「設置を確認できなかった」という誤検知になる(#1233)。
  # 対策として、kcadm の出力を変数へ丸ごと読み切ってから(この時点で kcadm は
  # 完全に書き終えて終了済み)grep をヒアストリングで実行する。ヒアストリングは
  # パイプではなく一時ファイル経由の入力なので、grep が早期終了しても
  # 書き込み側に SIGPIPE が飛ぶ余地がない。
  # `|| true` を付けないと、kcadm がSIGPIPEではなく本物のエラー(認可切れ等)で
  # 失敗したとき、この代入文自体はどの||/&&リストにも入っていないため
  # set -e がここで即座に発火し、下の placed_ok 診断へ落ちる前に無言で
  # 終了してしまう。
  kc_users_output="$(kcadm get users -r "$KEYCLOAK_REALM" --fields username 2>/dev/null)" || true
  grep -q "$PROBE_KC_USER" <<< "$kc_users_output" || placed_ok=0
  [ -n "$(mysql_q "SHOW DATABASES LIKE '${PROBE_MYSQL_DB}';" || true)" ] || placed_ok=0
  docker exec "$WORDPRESS_CONTAINER" sh -c "test -d ${PROBE_WP_DIR}" || placed_ok=0
  if [ "$placed_ok" -ne 1 ]; then
    echo "エラー: プローブを設置できたことを確認できませんでした。" >&2
    echo "       確認できないまま進むと、後の「消えている」が「作れていなかった」と区別できません。" >&2
    exit 1
  fi
  log "  確認: プローブ3種がいずれも存在します"
  PROBES_PLACED=1
else
  log "  プローブ省略(直前のスタックが起動していないため)"
fi

# ---------------------------------------------------------------- 1. 全撤去

step "1/5 スタックを撤去し、ボリュームを破棄します"

for rel in "${HOST_ARTIFACTS[@]}"; do
  if [ -e "$REPO_ROOT/$rel" ]; then
    rm -rf "${REPO_ROOT:?}/$rel"
    log "  削除: $rel(前回実行のホスト側生成物)"
  fi
done

down_out=""
# --profile '*' を付けるのは、docker-compose.yml が宣言する全プロファイル
# (comfyui の gpu 等)のコンテナも撤去の対象に含めるため(#1293)。プロファイル名を
# 個別に列挙する代わりに '*' を選んだのは、今後プロファイルが増減しても
# このスクリプトを追随させる必要がないようにするため。
if ! down_out="$(compose --profile '*' down --remove-orphans --timeout 60 2>&1)"; then
  printf '%s\n' "$down_out" | sed 's/^/  /'
  if printf '%s' "$down_out" | grep -q 'active endpoints'; then
    # 共有プロキシ(infra-proxy)が lbs-net に接続していると、ネットワークだけは削除できない。
    # コンテナは撤去されており、ネットワークは次の up で再利用される。想定内。
    log "  注意: 外部コンテナが接続しているためネットワークは残りました(想定内)"
  else
    echo "エラー: スタックの撤去に失敗しました。" >&2
    exit 1
  fi
fi
log "  撤去しました: compose プロジェクト ${COMPOSE_PROJECT}"

for v in "${DESTROY_VOLUMES[@]}"; do
  name="${VOLUME_PREFIX}${v}"
  if [ -z "$(volume_created_at "$name")" ]; then
    log "  (既に存在しません): $name"
    continue
  fi
  if docker volume rm "$name" >/dev/null 2>&1; then
    log "  破棄: $name"
  else
    refs="$(referencing_containers "$name")"
    if [ -n "$refs" ]; then
      echo "エラー: ボリューム $name を破棄できませんでした(参照しているコンテナ: ${refs})。" >&2
    else
      echo "エラー: ボリューム $name を破棄できませんでした(まだ使用中の可能性があります)。" >&2
    fi
    exit 1
  fi
done
# ボリューム破棄が完了した。以降のプローブ消失は破棄の成立の証拠なので、後片付けは行わない。
disarm_probe_cleanup
for v in "${PRESERVE_VOLUMES[@]}"; do
  log "  保全: ${VOLUME_PREFIX}${v}(削除しない)"
done

# ---------------------------------------------------------------- 2. 構築

step "2/5 ソースからビルドして起動します"
BUILD_START=$(date +%s)
if [ "$NO_CACHE" -eq 1 ]; then
  log "  docker compose build --no-cache(ビルドキャッシュを使いません)"
  compose build --no-cache
fi
# 必須サービスと任意サービスを**分けて**起動する。
#
# サービス無指定の `docker compose up -d` は、1つのサービスの起動に失敗した時点で
# **中断**する。GPU が無いホストでは comfyui が
# `could not select device driver "nvidia"` で落ちるため、依存関係の下流
# (web / gateway / keycloak / 各ドメインサービス)が created のまま残り、
# 健全性待ちが上限まで待ってから落ちる(2026-09-04 実測)。
#
# サービス一覧は compose 自身から取る(docker-compose.yml の写しを持たない)。
mapfile -t ALL_SERVICES < <(compose config --services | sort)
REQUIRED_TO_START=()
for s in "${ALL_SERVICES[@]}"; do
  skip=0
  for o in "${OPTIONAL_SERVICES[@]}"; do
    [ "$s" = "$o" ] && skip=1
  done
  [ "$skip" -eq 0 ] && REQUIRED_TO_START+=("$s")
done

UP_FAILED=0
compose up -d --build "${REQUIRED_TO_START[@]}" || UP_FAILED=1

# 任意サービスは最後に、失敗を許して起動する。起動できなくてもボリュームは作られるので、
# 破棄検証(CreatedAt)は成立する。
FAILED_OPTIONAL=()
for o in "${OPTIONAL_SERVICES[@]}"; do
  compose up -d "$o" >/dev/null 2>&1 || FAILED_OPTIONAL+=("$o")
done

BUILD_ELAPSED=$(( $(date +%s) - BUILD_START ))
log "  ビルドと起動: ${BUILD_ELAPSED} 秒"
if [ ${#FAILED_OPTIONAL[@]} -gt 0 ]; then
  log "  注意: 任意サービスが起動しませんでした: ${FAILED_OPTIONAL[*]}"
  log "        受け入れテストはこれらに依存しません(画像生成は image-stub が受けます)。"
fi
if [ "$UP_FAILED" -eq 1 ]; then
  log "  注意: 必須サービスの起動で compose が非0を返しました。"
  log "        どのサービスが healthy でないかは次の手順が判定します。"
fi

if [ "$SHARED_HOST" -eq 1 ]; then
  log "  共有ホスト構成のため scripts/setup-shared-host-proxy.sh を再適用します"
  # `LBS_BASE_URL=` で「適用後にベースURLへ届くこと」の確認を省く。
  # ここは up -d が返った直後で、web / gateway はまだ起動途中である。
  # 素の呼び出しでは 502 を掴んで非0終了し、ゼロ構築全体が止まる(2026-09-04 実測)。
  # 到達性の判定はリトライを持つ次の手順(wait-for-stack-healthy.sh)に委ねる。
  #
  # COMPOSE_PROJECT_NAME を明示するのは #1297: このスクリプトが隔離clone
  # (release-verify-tag.pyが`checkout-XXXX`に作るもの)から呼ばれると、
  # setup-shared-host-proxy.sh自身の既定(`${COMPOSE_PROJECT_NAME:-$(basename "$REPO_ROOT")}`)
  # がcloneのディレクトリ名に解決してしまい、共有スタックのネットワークを見失う。
  # このスクリプトの$COMPOSE_PROJECTは常に"lets_blog_server"に固定されているので、
  # それをそのまま渡せば呼び出し元のbasenameに依存しなくなる。
  LBS_BASE_URL="" COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" bash "$SCRIPT_DIR/setup-shared-host-proxy.sh" >/dev/null \
    || { echo "エラー: 共有プロキシの再適用に失敗しました。" >&2; exit 1; }
fi

# ---------------------------------------------------------------- 3. 健全性待ち

step "3/5 全サービスが healthy になるまで待ちます(最大 ${HEALTH_TIMEOUT_SECONDS} 秒)"
# COMPOSE_PROJECT_NAME を明示する理由は上の setup-shared-host-proxy.sh 呼び出しと同じ(#1297)。
if ! COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" "$SCRIPT_DIR/wait-for-stack-healthy.sh" --timeout "$HEALTH_TIMEOUT_SECONDS"; then
  echo "エラー: ゼロ構築後に healthy になりませんでした(上のサービス名を参照)。" >&2
  echo "       後続の段階は実行されません。docker compose logs <service> を確認してください。" >&2
  exit 1
fi

# ---------------------------------------------------------------- 4. 検証

step "4/5 ゼロ構築が成立したことを検証します"
verify_failed=0

# 4-1. 破棄対象が新しく作り直されていること。
for v in "${DESTROY_VOLUMES[@]}"; do
  name="${VOLUME_PREFIX}${v}"
  created="$(volume_created_at "$name")"
  if [ -z "$created" ]; then
    echo "  NG: $name が存在しません(構築で作り直されていない)" >&2
    verify_failed=1
    continue
  fi
  if [ "$(to_epoch "$created")" -lt "$START_TS" ]; then
    echo "  NG: $name の CreatedAt が実行開始より前です($created)。破棄されていません" >&2
    verify_failed=1
  fi
done
[ "$verify_failed" -eq 0 ] && log "  OK: 破棄対象のボリュームは全て CreatedAt が更新されています"

# 4-1b. 保全対象が作り直されていないこと。
for v in "${PRESERVE_VOLUMES[@]}"; do
  name="${VOLUME_PREFIX}${v}"
  before="${PRESERVE_VOLUMES_BEFORE[$v]}"
  after="$(volume_created_at "$name")"
  if [ -n "$before" ] && [ "$before" != "$after" ]; then
    echo "  NG: ${name} の CreatedAt が変わりました(保全できていない)" >&2
    verify_failed=1
  else
    log "  OK: ${name} は保全されています(CreatedAt ${after})"
  fi
done

# 4-3. 9スキーマに Flyway 管理テーブル以外のデータが無いこと。
#
# マイグレーションが投入するマスタデータは残っていて当然なので数えない。
# lbs_identity の roles / role_permissions は V2__seed_roles_and_permissions.sql が入れる
# RBAC の定義であり(#956)、これが空のほうが異常である。
# platform-service の ConnectionDefaultsSeeder(#1567)が起動のたびに書く system_settings のキー。
# ゼロ構築の直後に platform-service が起動して書く行は残骸ではないので数えない(#1701)。
# 表まるごとではなくキーで除外する: 利用者が管理画面で保存した設定の残りは見逃さない。
# シーダーのキーを変えたら、ここも変えること(test_rebuild_acceptance_env.py が突き合わせる)。
SEEDED_SETTING_KEYS=(llm_ollama_base_url comfyui_base_url)
seeded_in_list="$(printf "'%s'," "${SEEDED_SETTING_KEYS[@]}")"
seeded_in_list="${seeded_in_list%,}"
schema_dirty=0
for s in "${SERVICE_SCHEMAS[@]}"; do
  tables="$(mysql_q "SELECT table_name FROM information_schema.tables
                     WHERE table_schema='$s'
                       AND table_name NOT IN ('flyway_schema_history', 'roles', 'role_permissions');" || true)"
  total=0
  for t in $tables; do
    where=""
    if [ "$s" = "lbs_platform" ] && [ "$t" = "system_settings" ]; then
      where=" WHERE setting_key NOT IN ($seeded_in_list)"
    fi
    c="$(mysql_q "SELECT COUNT(*) FROM \`$s\`.\`$t\`${where};" || echo 0)"
    total=$(( total + c ))
  done
  if [ "$total" -ne 0 ]; then
    echo "  NG: $s に ${total} 行残っています" >&2
    schema_dirty=1
    verify_failed=1
  fi
done
[ "$schema_dirty" -eq 0 ] && log "  OK: 9スキーマにドメインデータが残っていません"

# 4-3b. *_test スキーマが 02-create-test-schemas.sh で作り直されていること。
all_databases="$(mysql_q "SHOW DATABASES;" || true)"
missing_test=""
for s in "${SERVICE_SCHEMAS[@]}"; do
  printf '%s\n' "$all_databases" | grep -qx "${s}_test" || missing_test="${missing_test} ${s}_test"
done
if [ -n "$missing_test" ]; then
  echo "  NG: *_test スキーマが作り直されていません:${missing_test}" >&2
  echo "      infra/mysql/init/02-create-test-schemas.sh が走っていない可能性があります。" >&2
  verify_failed=1
else
  log "  OK: *_test スキーマ(ホストからの ./gradlew test 用)が作り直されています"
fi

# 4-4. letsblog レルムが存在し、ユーザーがサービスアカウントだけであること。
if ! kcadm_login; then
  echo "  NG: Keycloak 管理 CLI にログインできません" >&2
  verify_failed=1
elif ! kcadm get "realms/$KEYCLOAK_REALM" --fields realm >/dev/null 2>&1; then
  echo "  NG: ${KEYCLOAK_REALM} レルムが存在しません(realm-export.json の再インポートに失敗)" >&2
  verify_failed=1
else
  unexpected="$(kcadm get users -r "$KEYCLOAK_REALM" --fields id,username,email 2>/dev/null \
    | ALLOWED="$ALLOWED_KEYCLOAK_USER" python3 -c "
import json, os, sys
allowed = os.environ['ALLOWED']
try:
    users = json.load(sys.stdin)
except Exception:
    users = []
print(' '.join(
    (u.get('email') or u.get('username') or '?')
    for u in users
    if (u.get('username') or '') != allowed
))
")"
  if [ -n "$unexpected" ]; then
    echo "  NG: ${KEYCLOAK_REALM} レルムに想定外のユーザーが残っています: $unexpected" >&2
    verify_failed=1
  else
    log "  OK: ${KEYCLOAK_REALM} レルムのユーザーは ${ALLOWED_KEYCLOAK_USER} のみです"
  fi
fi

# 4-5. WordPress にサイト実体が無いこと。
wp_left="$(docker exec "$WORDPRESS_CONTAINER" sh -c 'ls -1A /var/www/html/sites 2>/dev/null' || true)"
if [ -n "$wp_left" ]; then
  echo "  NG: WordPress にサイトが残っています: $(printf '%s' "$wp_left" | tr '\n' ' ')" >&2
  verify_failed=1
else
  log "  OK: WordPress にサイト実体がありません"
fi

# 4-6. 初回セットアップ導線が gateway 経由で到達でき、まだ誰も居ないこと。
#
# #951(gateway の DNS キャッシュによる転送先取り違え)の検出点として #945 が置いたもの。
# 全コンテナを作り直すゼロ構築では、この検出はより重要になる。
setup_response="$(curl -sk -m 20 -w '\n%{http_code}' "$BASE_URL/api/auth/setup-status" || printf '\n000')"
setup_code="$(printf '%s' "$setup_response" | tail -n1)"
setup_body="$(printf '%s' "$setup_response" | sed '$d')"
if [ "$setup_code" != "200" ]; then
  echo "  NG: GET /api/auth/setup-status が ${setup_code} を返しました(200 を期待)" >&2
  echo "      gateway が古い転送先を掴んだままの可能性があります(#951)。" >&2
  verify_failed=1
elif ! printf '%s' "$setup_body" | grep -q '"needsSetup"[[:space:]]*:[[:space:]]*true'; then
  echo "  NG: setup-status が needsSetup: true を返しません(応答: ${setup_body})" >&2
  echo "      ゼロ構築直後はユーザーが0人のはずです。" >&2
  verify_failed=1
else
  log "  OK: GET /api/auth/setup-status → 200 / needsSetup: true"
fi

# 4-7. プローブが3つとも消えていること。
if [ "$PROBES_PLACED" -eq 1 ]; then
  remaining=""
  # `kcadm get users | grep -q` のパイプだと、プローブが残っている異常系で grep -q が
  # 最初の一致で早期終了し、書き込み中の kcadm が SIGPIPE(141)を受ける。
  # `set -o pipefail` 下ではパイプ全体が非0になり `&& remaining=...` が実行されず、
  # 本来報告すべき残存の名指しが消える(#1233 と同種、#1277)。
  # そこで出力を変数へ読み切ってから、ヒアストリングで grep する。
  # `|| true` は kcadm 自体の失敗で set -e が発火するのを避けるため(手順0/5と同じ)。
  kc_users_after="$(kcadm get users -r "$KEYCLOAK_REALM" --fields username 2>/dev/null)" || true
  if grep -q "$PROBE_KC_USER" <<< "$kc_users_after"; then
    remaining="${remaining}\n  - Keycloak ユーザー ${PROBE_KC_USER}(keycloak_postgres が破棄されていない)"
  fi
  if [ -n "$(mysql_q "SHOW DATABASES LIKE 'at\\_wipe\\_probe\\_%';" || true)" ]; then
    remaining="${remaining}\n  - MySQL データベース ${PROBE_MYSQL_DB}(mysql_data が破棄されていない)"
  fi
  if docker exec "$WORDPRESS_CONTAINER" sh -c "test -d ${PROBE_WP_DIR}" 2>/dev/null; then
    remaining="${remaining}\n  - WordPress ディレクトリ ${PROBE_WP_DIR}(wordpress_sites が破棄されていない)"
  fi
  if [ -n "$remaining" ]; then
    echo "  NG: 撤去したはずのプローブが残っています:" >&2
    printf "%b\n" "$remaining" >&2
    verify_failed=1
  else
    log "  OK: プローブ3種はいずれも消えています(撤去が成立しました)"
  fi
else
  log "  プローブ省略のため、プローブによる撤去検証は行いません(CreatedAt 検査で判定済み)"
fi

if [ "$verify_failed" -ne 0 ]; then
  echo "" >&2
  echo "エラー: ゼロ構築が成立していません(上の NG を参照)。後続の段階は実行されません。" >&2
  exit 1
fi

# ---------------------------------------------------------------- 5. 完了

ELAPSED=$(( $(date +%s) - START_TS ))
step "5/5 完了"
log "所要時間: ${ELAPSED} 秒(うちビルドと起動 ${BUILD_ELAPSED} 秒)"
log "次: 受け入れテストの段階実行(at-setup → at-seed → at-provision → at-main → at-destructive)"
