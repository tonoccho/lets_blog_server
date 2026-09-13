#!/bin/bash
# 外部HTTP(S)呼び出しを行うサービスコンテナに、Dockerの既定bridgeネットワーク経由で
# インターネットへのデフォルトルートを追加する。
#
# 背景: lbs-net(このプロジェクト用のuser-definedブリッジ)だけでは、開発環境によっては
# 外部インターネットへ出られないことがある。#575でモノリス(旧apiコンテナ)がマイクロサービスへ
# 分割された後も、以下のような外部呼び出しを行うサービスは同じ制約を受ける(#1205):
#   - lbs-ai: 外部LLM/検索API(api.anthropic.com / api.github.com / api.search.brave.com)
#   - lbs-analytics: Google系API(analyticsdata.googleapis.com / adsense.googleapis.com 等)
#   - lbs-content: Playwright経由の外部ページ取得([blogcard]/[amazon]スクレイピング、OGP取得)
#   - lbs-media: 外部画像生成API(ChatGptImageClient)
#   - lbs-platform: 外部LLM API(api.anthropic.com / api.openai.com)
#   - lbs-publishing: 記事プレビューの外部取得、管理対象サイトが外部ホストの場合のWordPress操作
#   - lbs-wordpress: wordpress.orgからのプラグイン/テーマ取得
#   - lbs-ollama: モデルpull(`ollama pull`はOllamaコンテナ自身がregistry.ollama.aiへ
#     取得しにいくため)
# docker-compose.ymlのservices.*.networksでDockerの既定bridgeネットワークを直接指定すると
# 「network-scoped aliases are only supported for user-defined networks」エラーになり
# 宣言的に書けないため、`docker network connect`で後付けする。
#
# 使い方: `docker compose up -d` の後にこのスクリプトを実行する
# (`docker compose up --force-recreate`等で対象コンテナが作り直された場合は再実行が必要)。
# 二重接続はDockerがエラーにするだけで実害はないため、既に繋がっていれば無視して続行する。
#
# 対象コンテナが1つも見つからない場合はサイレントに成功したように見せず、非0で終了する
# (#1205: 対象一覧が古くなって全滅していても`exit 0`していたため、外部疎通の欠落に
# 誰も気づけなかった)。
set -uo pipefail

TARGET_CONTAINERS=(
  lbs-ai
  lbs-analytics
  lbs-content
  lbs-media
  lbs-platform
  lbs-publishing
  lbs-wordpress
  lbs-ollama
)

connected_or_present_count=0

for container in "${TARGET_CONTAINERS[@]}"; do
  if ! docker inspect "$container" >/dev/null 2>&1; then
    echo "スキップ: コンテナ ${container} が見つかりません(起動していますか?)"
    continue
  fi
  connected_or_present_count=$((connected_or_present_count + 1))
  if docker network connect bridge "$container" 2>/dev/null; then
    echo "接続しました: ${container} -> bridge"
  else
    echo "既に接続済み、またはスキップ: ${container} -> bridge"
  fi
done

if [ "$connected_or_present_count" -eq 0 ]; then
  echo "エラー: 対象コンテナが1つも見つかりませんでした(${TARGET_CONTAINERS[*]})。" >&2
  echo "docker compose up -d を実行済みか確認してください。" >&2
  exit 1
fi
