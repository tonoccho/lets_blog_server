#!/bin/bash
# https://localhost 用の自己署名TLS証明書を生成する(開発環境専用)。
# 既存の証明書・秘密鍵があれば再生成せず終了する。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERTS_DIR="$SCRIPT_DIR/../certs"
CERT_FILE="$CERTS_DIR/localhost.crt"
KEY_FILE="$CERTS_DIR/localhost.key"

mkdir -p "$CERTS_DIR"

if [ -f "$CERT_FILE" ] && [ -f "$KEY_FILE" ]; then
  echo "証明書は既に存在します(再生成する場合は certs/ 配下を削除してから再実行してください): $CERT_FILE"
  exit 0
fi

openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout "$KEY_FILE" \
  -out "$CERT_FILE" \
  -subj "/C=JP/ST=Tokyo/L=Tokyo/O=LetsBlog/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

chmod 600 "$KEY_FILE"

echo "自己署名証明書を生成しました:"
echo "  証明書: $CERT_FILE"
echo "  秘密鍵: $KEY_FILE"
