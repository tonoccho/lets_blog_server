#!/bin/bash

set -euo pipefail

# マルチサービス構成向けAPIクライアント生成(#555)。
# 手順とトラブルシュートは docs/API_CLIENT_GENERATION.md を参照。
#
# ── 取得方法 ────────────────────────────────────────────────────────────────
# 各サービスは springdoc の既定パス /v3/api-docs で OpenAPI spec を公開するが、
# docker-compose.yml ではどのサービスも **ホストへポートを公開していない**
# (gateway も /v3/api-docs をルーティングしない)。そのため既定では
# `docker exec <コンテナ> curl http://localhost:8080/v3/api-docs` で
# コンテナ内から取得する。開発スタックが起動していればそのまま動く(issue #811)。
#
# ── 環境変数で上書きする場合 ──────────────────────────────────────────────
# サービスへ直接到達できる URL がある場合(ポートを公開している、リモートで動かして
# いる等)は、以下の環境変数にベースURLを設定するとそちらから取得する。
# 設定しなければ上記の docker exec 経由になる。
#
#   LOG_WRITER_URL         log-writer
#   MEDIA_SERVICE_URL      media
#   AI_SERVICE_URL         ai
#   CONTENT_SERVICE_URL    content
#   ANALYTICS_SERVICE_URL  analytics
#   PROJECT_SERVICE_URL    project
#   PUBLISHING_SERVICE_URL publishing
#   PLATFORM_SERVICE_URL   platform
#   IDENTITY_SERVICE_URL   identity
#
# 注意: すべて同じ URL を指したまま実行すると、同じ spec が10回別名で保存され、
# エラーにならないまま壊れた生成物ができる。それを検知するため、取得後に
# 内容の重複を検査して落とす(#811。以前は全サービスの既定値が
# http://localhost:8080 で、まさにこの状態になりえた)。
#
# issue #583 で legacy-api を削除したため、対象は9サービスになった。
#
# "サービス名|コンテナ名|URL上書き環境変数の値" の形式で列挙する。
# サービスを増やすときはここに1行足す(config/orval.config.js 側にもターゲット定義が必要)。
SERVICES=(
  "log-writer|lbs-log-writer|${LOG_WRITER_URL:-}"
  "media|lbs-media|${MEDIA_SERVICE_URL:-}"
  "ai|lbs-ai|${AI_SERVICE_URL:-}"
  "content|lbs-content|${CONTENT_SERVICE_URL:-}"
  "analytics|lbs-analytics|${ANALYTICS_SERVICE_URL:-}"
  "project|lbs-project|${PROJECT_SERVICE_URL:-}"
  # 公開パイプライン・一括管理・記事プレビューのCMS依存部分をpublishing-serviceへ移設
  # (#707/#708/#709/#712)。
  "publishing|lbs-publishing|${PUBLISHING_SERVICE_URL:-}"
  # VSCode拡張配布・バックアップ・システム設定・ダッシュボード状態をplatform-serviceへ移設
  # (#693〜#696、C10)。
  "platform|lbs-platform|${PLATFORM_SERVICE_URL:-}"
  # ユーザー・ロール・権限管理をidentity-serviceへ移設(#561)。
  "identity|lbs-identity|${IDENTITY_SERVICE_URL:-}"
)

# gatewayは対象外。自身のコントローラを持たず、springdocも導入していない
# (services/gateway/build.gradle にspringdoc依存が無い)。ルーティング先の各サービスの
# specを個別に取得すれば足りる。

OPENAPI_DIR="openapi"
SPEC_PATH="/v3/api-docs"
# springdoc が返す servers[0].url はリクエストのホスト由来で、取得経路によって
# http://ai:8080 になったり http://localhost:8080 になったりする。生成コードは
# orval の output.baseUrl を使うため動作には影響しないが、再生成のたびに無意味な差分が
# 出てレビューのノイズになるため固定値へ正規化する(#811)。
NORMALIZED_SERVER_URL="http://localhost:8080"
MAX_RETRIES=30
RETRY_DELAY=2

for cmd in curl jq; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "❌ $cmd が必要です。インストールしてから再実行してください。" >&2
    exit 1
  fi
done

mkdir -p "$OPENAPI_DIR"

# 1回分の取得。成功なら spec を標準出力へ、失敗なら非0で返す。
fetch_spec() {
  local container="$1" base_url="$2"
  if [ -n "$base_url" ]; then
    curl -sf "${base_url}${SPEC_PATH}"
  else
    docker exec "$container" curl -sf "http://localhost:8080${SPEC_PATH}"
  fi
}

for entry in "${SERVICES[@]}"; do
  service="${entry%%|*}"
  rest="${entry#*|}"
  container="${rest%%|*}"
  base_url="${rest#*|}"
  spec_file="${OPENAPI_DIR}/${service}.json"

  if [ -n "$base_url" ]; then
    source_desc="${base_url}${SPEC_PATH}"
  else
    source_desc="docker exec ${container} -> http://localhost:8080${SPEC_PATH}"
  fi
  echo "📡 [$service] Fetching OpenAPI spec from: $source_desc"

  retry_count=0
  fetched=false
  while [ $retry_count -lt $MAX_RETRIES ]; do
    if raw="$(fetch_spec "$container" "$base_url" 2>/dev/null)" && [ -n "$raw" ]; then
      fetched=true
      break
    fi
    retry_count=$((retry_count + 1))
    if [ $retry_count -lt $MAX_RETRIES ]; then
      echo "⏳ [$service] Waiting for service ($retry_count/$MAX_RETRIES)..."
      sleep $RETRY_DELAY
    fi
  done

  if [ "$fetched" != "true" ]; then
    echo "❌ [$service] Failed to fetch OpenAPI spec after $MAX_RETRIES attempts: $source_desc" >&2
    if [ -n "$base_url" ]; then
      echo "   環境変数で指定した URL に到達できません。値を確認してください。" >&2
    else
      echo "   コンテナ ${container} が起動していないか、springdoc が応答していません。" >&2
      echo "   docker compose up -d でスタックを起動してから再実行してください。" >&2
      echo "   (詳細: docs/API_CLIENT_GENERATION.md)" >&2
    fi
    exit 1
  fi

  if ! printf '%s' "$raw" | jq empty 2>/dev/null; then
    echo "❌ [$service] Invalid OpenAPI spec (not valid JSON): $source_desc" >&2
    exit 1
  fi

  # servers を正規化してから保存する(取得経路による差分を消す)。
  # url だけを差し替える(description 等 springdoc が付ける他のキーは保つ)。
  printf '%s' "$raw" \
    | jq --arg url "$NORMALIZED_SERVER_URL" '.servers |= map(.url = $url)' \
    > "$spec_file"

  echo "✅ [$service] OpenAPI spec downloaded successfully"
done

# 全サービスが同じ spec を返していないか検査する。環境変数をすべて同じ URL に
# 設定した場合や、取得先を1サービスへ向けたままにした場合、エラーにならないまま
# 「同じ spec が10個」という壊れた状態になるため、ここで気づけるようにする(#811)。
echo "🔎 Checking that each service returned a distinct spec..."
duplicates="$(
  for entry in "${SERVICES[@]}"; do
    service="${entry%%|*}"
    # servers は正規化済みなので、内容が同一なら同じ spec を指している。
    printf '%s %s\n' "$(jq -S -c . "${OPENAPI_DIR}/${service}.json" | sha256sum | cut -d' ' -f1)" "$service"
  done | sort | awk '{h[$1]=h[$1]" "$2} END {for (k in h) if (split(h[k], a, " ") > 2) print h[k]}'
)"
if [ -n "$duplicates" ]; then
  echo "❌ 複数のサービスが同一の spec を返しました:" >&2
  echo "$duplicates" | sed 's/^/   /' >&2
  echo "   取得先が同じサービスを指しています。環境変数(スクリプト冒頭のコメント参照)か" >&2
  echo "   コンテナ名を確認してください。詳細: docs/API_CLIENT_GENERATION.md" >&2
  exit 1
fi
echo "✅ All specs are distinct"

echo "🔨 Generating TypeScript client with orval (all targets)..."

# orval CLI のバージョンを固定する(#1006)。
#
# このスクリプトはリポジトリルートで `npx orval` を呼ぶが、ルートには package.json も
# node_modules も無い。そのため npx は**毎回レジストリの latest を取りに行く**。
# 生成物をコミットしている(docs/API_CLIENT_GENERATION.md「コミットするもの」)以上、
# これは「実行した日によって差分が出る」ということであり、再現性が無い。
#
# 実際に壊れた: 2026-09-03 時点の latest である orval@8.28.0 は、依存する
# @orval/angular@8.28.0 が未公開のまま公開されており、`npx orval` は ETARGET で失敗した
# (数時間後に 8.28.1 が出て解消したが、それも latest 追従では防げない)。
#
# 8.27.0 を選ぶ理由:
#   - GHSA-h526-wf6g-67jv(@orval/core の code injection)の修正版であること
#   - コミット済みの packages/api-client/src/generated/** を1バイトも変えずに再生成できる
#     こと(8.28.x は multipart の Blob を Blob | File へ広げ、サービスごとに index.ts を
#     新規生成するため、生成物に差分が出る。それはそれで別途レビューすべき変更である)
#   - apps/web が devDependencies に宣言する @orval/core / @orval/fetch と同一であること
#     (apps/web/dependency-advisories.test.ts がこの一致を固定している)
#
# 上げるときは apps/web/package.json の @orval/* と一緒に上げ、生成物の差分をレビューする。
ORVAL_VERSION="8.27.0"

# 出力先ディレクトリはconfig/orval.config.js側の各ターゲットが作成するため、ここでは
# ルートの出力先だけ用意しておく。
mkdir -p "packages/api-client/src/generated"

npx --yes "orval@${ORVAL_VERSION}" --config config/orval.config.js

echo "✅ API client generation complete"
echo "📁 Generated client: packages/api-client/src/generated/"
