#!/bin/bash

set -e

# マルチサービス構成向けAPIクライアント生成(#555)。
# "サービス名|ベースURL" の形式で、生成対象のサービスを列挙する。各サービスは
# springdocの既定パス(/v3/api-docs)でOpenAPI specを公開する規約(docs/API_CLIENT_GENERATION.md)。
# 将来のサービス抽出Issue(Phase 19)で、ここに1行追加するだけでターゲットを増やせる
# (orval.config.js側にも対応するターゲット定義の追加が必要)。
SERVICES=(
  "legacy-api|${LETS_BLOG_API_URL:-http://localhost:8080}"
  "log-writer|${LOG_WRITER_URL:-http://localhost:8080}"
  "media|${MEDIA_SERVICE_URL:-http://localhost:8080}"
  "ai|${AI_SERVICE_URL:-http://localhost:8080}"
  "content|${CONTENT_SERVICE_URL:-http://localhost:8080}"
  "analytics|${ANALYTICS_SERVICE_URL:-http://localhost:8080}"
  "project|${PROJECT_SERVICE_URL:-http://localhost:8080}"
  # 公開パイプライン・一括管理・記事プレビューのCMS依存部分をpublishing-serviceへ移設
  # (#707/#708/#709/#712)。
  "publishing|${PUBLISHING_SERVICE_URL:-http://localhost:8080}"
  # VSCode拡張配布・バックアップ・システム設定・ダッシュボード状態をplatform-serviceへ移設
  # (#693〜#696、C10)。
  "platform|${PLATFORM_SERVICE_URL:-http://localhost:8080}"
  # ユーザー・ロール・権限管理をidentity-serviceへ移設(#561)。
  "identity|${IDENTITY_SERVICE_URL:-http://localhost:8080}"
)

# gatewayは対象外。自身のコントローラを持たず、springdocも導入していない
# (services/gateway/build.gradle にspringdoc依存が無い)。ルーティング先の各サービスの
# specを個別に取得すれば足りる。

OPENAPI_DIR="openapi"
MAX_RETRIES=30
RETRY_DELAY=2

mkdir -p "$OPENAPI_DIR"

for entry in "${SERVICES[@]}"; do
  service="${entry%%|*}"
  base_url="${entry#*|}"
  spec_url="${base_url}/v3/api-docs"
  spec_file="${OPENAPI_DIR}/${service}.json"

  echo "📡 [$service] Fetching OpenAPI spec from: $spec_url"

  retry_count=0
  fetched=false
  while [ $retry_count -lt $MAX_RETRIES ]; do
    if curl -s -f "$spec_url" -o "$spec_file"; then
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
    echo "❌ [$service] Failed to fetch OpenAPI spec after $MAX_RETRIES attempts: $spec_url"
    echo "   '$service' サービスが起動していないか、$spec_url で応答していません。"
    echo "   docker compose でサービスを起動し、$spec_url に到達できる状態にしてから再実行してください。"
    exit 1
  fi

  if ! jq empty "$spec_file" 2>/dev/null; then
    echo "❌ [$service] Invalid OpenAPI spec (not valid JSON): $spec_file"
    exit 1
  fi

  echo "✅ [$service] OpenAPI spec downloaded successfully"
done

echo "🔨 Generating TypeScript client with orval (all targets)..."

# 出力先ディレクトリはorval.config.js側の各ターゲットが作成するため、ここでは
# ルートの出力先だけ用意しておく。
mkdir -p "sdk/api-client/src/generated"

npx orval

echo "✅ API client generation complete"
echo "📁 Generated client: sdk/api-client/src/generated/"
