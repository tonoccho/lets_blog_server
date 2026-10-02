#!/usr/bin/env bash
# Penpotプラグインのセットアップ(issue #1491)。
# ダウンロードしたZipを展開し、展開したディレクトリで `bash setup.sh` を実行すると、
# 依存の取得とビルドが走り、Penpotへ登録できる状態になる。
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

echo "==> 依存を取得します (npm ci)"
npm ci
echo "==> プラグインをビルドします (plugin.js)"
npm run build
echo "==> UI をビルドします (ui.js)"
npm run build:ui

cat <<MSG

セットアップが完了しました。plugin.js と ui.js を生成しました。

Penpot への登録手順:
  1. Penpot を開く
  2. Plugins → Add Plugin を選ぶ
  3. 次の manifest.json を選ぶ:
       ${ROOT}/manifest.json
  4. Install を押す
  "AI Design Assistant" がプラグインメニューに表示されます。

利用には MCP サーバー(http://localhost:3000)が必要です。接続先を変える場合は README.md の
Configuration を参照してください。
MSG
