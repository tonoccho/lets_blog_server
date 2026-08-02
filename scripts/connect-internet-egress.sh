#!/bin/bash
# api/wordpressコンテナに、Dockerの既定bridgeネットワーク経由でインターネットへの
# デフォルトルートを追加する。
#
# 背景: lbs-net(このプロジェクト用のuser-definedブリッジ)だけでは、開発環境によっては
# 外部インターネットへ出られないことがある(非managedサイトへのSSH/REST疎通、
# wordpress.orgからのプラグイン/テーマ取得に必要)。docker-compose.ymlのservices.*.networks
# でDockerの既定bridgeネットワークを直接指定すると
# 「network-scoped aliases are only supported for user-defined networks」エラーになり
# 宣言的に書けないため、`docker network connect`で後付けする。
#
# 使い方: `docker compose up -d` の後にこのスクリプトを実行する
# (`docker compose up --force-recreate`等でapi/wordpressコンテナが作り直された場合は
# 再実行が必要)。二重接続はDockerがエラーにするだけで実害はないため、
# 既に繋がっていれば無視して続行する。
set -uo pipefail

for container in lbs-api lbs-wordpress; do
  if ! docker inspect "$container" >/dev/null 2>&1; then
    echo "スキップ: コンテナ ${container} が見つかりません(起動していますか?)"
    continue
  fi
  if docker network connect bridge "$container" 2>/dev/null; then
    echo "接続しました: ${container} -> bridge"
  else
    echo "既に接続済み、またはスキップ: ${container} -> bridge"
  fi
done
