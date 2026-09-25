#!/bin/bash
# 受け入れテストの前処理: system_settings(DB)にあるLLM/画像生成(ComfyUI含む)の
# 接続設定を削除する (issue #928 / AT-2)。
#
# なぜ必要か:
#   LLM と画像生成の接続設定は「DB(system_settings)があればDB、無ければ環境変数の既定値」
#   という優先順位で解決される(platform-service の AppSettingService が正)。
#   したがって docker-compose.e2e-stubs.yml で LLM_BASE_URL を差し替えても、
#   システム設定画面で一度でも値を保存していると **実サービスへ出ていく**。
#   実キーが設定されていれば課金が発生し、設定されていなければ受け入れテストが不可解に落ちる。
#
#   この落とし穴は #843 のスタブ導入時から存在し、コメントで注意書きするだけだった。
#   手順として実行できる形にする。
#
# 何を消すか:
#   system_settings の llm_* / image_llm_* / comfyui_base_url / upload_rate_limit_requests
#   の行だけ。他の設定(メール・Webの公開URL等)には触れない。
#   行を消すと環境変数の既定値へ戻る。
#
#   comfyui_base_url も対象である(#1106)。ComfyUI の向き先も管理APIから保存できる
#   設定キーなので、行が入ると docker-compose.e2e-stubs.yml の
#   COMFYUI_BASE_URL=http://comfyui-stub:8080 が黙って無視され、comfyui-stub ではなく
#   実 ComfyUI(GPUの無いホストには存在しない)へ出ていく。
#
#   upload_rate_limit_requests も対象である(#1286 / #1351)。gateway 自体は
#   UPLOAD_RATE_LIMIT_REQUESTS を環境変数からしか読まず(DBは読まない)ので、この行が
#   残っていても現状の gateway の挙動は変わらない。対象にするのは
#   「overlay が差し替える設定キーは全てDBから消す」という不変条件を保つためであり、
#   AppSettingService 側からは resolve() がDB優先で解決するため、管理画面から値が
#   保存されていれば AppSettingService 経由の参照はその値を返す。
#
# 使い方:
#   ./scripts/e2e-clear-llm-db-overrides.sh          # ドライラン(消す行を表示するだけ)
#   ./scripts/e2e-clear-llm-db-overrides.sh --yes    # 実際に削除する
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
ENV_FILE="$REPO_ROOT/.env"
MYSQL_CONTAINER="lbs-mysql"
APPLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --yes) APPLY=1; shift ;;
    *) echo "エラー: 不明な引数 '$1'" >&2; exit 1 ;;
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

# AppSettingService が定義するキーのうち、外部サービスへの向き先と資格情報に関わるもの。
# 追加したキーはここにも足すこと(足し忘れると、そのキーだけDB値が残り実サービスへ出ていく)。
KEYS="'llm_api_key','llm_base_url','llm_model','llm_available_models',\
'llm_request_timeout_seconds','llm_provider','llm_claude_api_key','llm_claude_model',\
'llm_ollama_base_url','llm_ollama_model',\
'image_llm_api_key','image_llm_base_url',\
'comfyui_base_url','upload_rate_limit_requests'"

run_sql() {
  docker exec -i "$MYSQL_CONTAINER" \
    mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -N -B -e "$1" 2>/dev/null
}

EXISTING="$(run_sql "SELECT setting_key FROM lbs_platform.system_settings WHERE setting_key IN ($KEYS);" || true)"

if [ -z "$EXISTING" ]; then
  echo "OK: system_settings にLLM/画像生成(ComfyUI含む)の上書きはありません(環境変数の既定値が使われます)"
  exit 0
fi

echo "system_settings に以下の上書きがあります:"
echo "$EXISTING" | sed 's/^/  - /'

if [ "$APPLY" -eq 0 ]; then
  echo
  echo "ドライランのため削除していません。実行するには --yes を付けてください。"
  echo "  ./scripts/e2e-clear-llm-db-overrides.sh --yes"
  exit 0
fi

run_sql "DELETE FROM lbs_platform.system_settings WHERE setting_key IN ($KEYS);" >/dev/null
echo
echo "削除しました。platform-service の設定キャッシュを捨てるため再起動します。"
docker restart lbs-platform >/dev/null
echo "OK: 環境変数(docker-compose.e2e-stubs.yml のスタブ向き先)が有効になりました。"
