#!/usr/bin/env bash
# MCPサーバーのセットアップ(issue #1491)。
# ダウンロードしたZipを展開し、展開したディレクトリで `bash setup.sh` を実行すると、
# 依存の取得と .env の用意が済み、`npm start` で起動できる状態になる。
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
ROOT="$(pwd)"
REQUIRED_NODE_MAJOR=20

fail() {
  echo "エラー: $*" >&2
  exit 1
}

command -v node >/dev/null 2>&1 || fail "Node.js ${REQUIRED_NODE_MAJOR} 以上が必要ですが、node が見つかりません。https://nodejs.org/ から導入してください。"
command -v npm >/dev/null 2>&1 || fail "npm が見つかりません。Node.js ${REQUIRED_NODE_MAJOR} 以上(npm 同梱)を導入してください。"

NODE_VERSION="$(node -v)"
NODE_MAJOR="${NODE_VERSION#v}"
NODE_MAJOR="${NODE_MAJOR%%.*}"
if ! [[ "${NODE_MAJOR}" =~ ^[0-9]+$ ]] || [ "${NODE_MAJOR}" -lt "${REQUIRED_NODE_MAJOR}" ]; then
  fail "Node.js ${REQUIRED_NODE_MAJOR} 以上が必要です(現在: ${NODE_VERSION})。Node.js を更新してから再実行してください。"
fi

echo "==> 依存を取得します (npm ci --omit=dev)"
npm ci --omit=dev

if [ -f .env ]; then
  echo "==> 既存の .env を残します"
else
  echo "==> .env.example から .env を作ります"
  cp .env.example .env
fi

cat <<MSG

セットアップが完了しました。

起動手順:
  1. ${ROOT}/.env を編集し、接続先を環境に合わせる
       OLLAMA_BASE_URL=http://ollama:11434   (Ollama の URL)
       OLLAMA_MODEL=qwen2.5:7b-instruct
  2. サーバーを起動する:
       cd ${ROOT}
       npm start
  3. 動作確認: curl http://localhost:3000/health
MSG
