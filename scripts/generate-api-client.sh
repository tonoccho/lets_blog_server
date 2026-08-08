#!/bin/bash

set -e

API_URL="${LETS_BLOG_API_URL:-http://localhost:8080}"
SPEC_URL="${API_URL}/v3/api-docs"
SPEC_FILE="openapi.json"
OUTPUT_DIR="sdk/api-client/src/generated"

echo "📡 Fetching OpenAPI spec from: $SPEC_URL"

# Fetch OpenAPI spec with retries
MAX_RETRIES=30
RETRY_DELAY=2
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
  if curl -s -f "$SPEC_URL" -o "$SPEC_FILE"; then
    echo "✅ OpenAPI spec downloaded successfully"
    break
  else
    RETRY_COUNT=$((RETRY_COUNT + 1))
    if [ $RETRY_COUNT -lt $MAX_RETRIES ]; then
      echo "⏳ Waiting for API server ($RETRY_COUNT/$MAX_RETRIES)..."
      sleep $RETRY_DELAY
    fi
  fi
done

if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
  echo "❌ Failed to fetch OpenAPI spec after $MAX_RETRIES attempts"
  exit 1
fi

# Check if spec file is valid JSON
if ! jq empty "$SPEC_FILE" 2>/dev/null; then
  echo "❌ Invalid OpenAPI spec (not valid JSON)"
  exit 1
fi

echo "🔨 Generating TypeScript client with orval..."

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Run orval to generate client
npx orval

echo "✅ API client generation complete"
echo "📁 Generated client: $OUTPUT_DIR"
