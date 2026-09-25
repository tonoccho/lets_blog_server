#!/bin/sh
# docker-compose.ymlのwebサービスは ./apps/web:/app をバインドマウントするため、
# Dockerfileの `RUN npm ci` がイメージ内に作った /app/node_modules はマウントで
# 覆い隠される。クローン直後などホストに apps/web/node_modules が無い状態では、
# コンテナ内(node:22-alpine、musl)で改めて npm ci する必要がある(#1050)。
#
# ホストのNodeとコンテナのNode(musl)のネイティブバイナリ非互換を避けるため、
# インストールは常にこのコンテナ内で行う。生成物はrootが作るため、バインドマウント元の
# 所有者(=ホストの実行ユーザー)へchownし直す。
#
# npm ci/chownにはrootが必要な一方、その後 `exec "$@"`(next dev)をrootのまま
# 実行し続けると、devサーバーが実行中に作り続ける apps/web/.next もroot所有になり、
# ホスト(uid 1000)から `npm run build` がEACCESで落ちる(#1042)。そのため、
# 最終的なコマンドはsu-execでバインドマウント元の所有者(ホストの実行ユーザー)へ
# 権限を落としてから実行する。
set -e

# 既定は /app(Dockerfile の WORKDIR/バインドマウント先)。scripts/test_web_docker_entrypoint.py
# が権限昇格なしに検証できるよう、テストからのみ上書きできるようにしてある(本番の起動では
# 常に既定値のまま)。
APP_DIR="${APP_DIR:-/app}"

HOST_UID="$(stat -c '%u' "$APP_DIR")"
HOST_GID="$(stat -c '%g' "$APP_DIR")"

if [ ! -x "$APP_DIR/node_modules/.bin/next" ]; then
  ( cd "$APP_DIR" && npm ci --legacy-peer-deps )
  chown -R "$HOST_UID:$HOST_GID" "$APP_DIR/node_modules"
fi

exec su-exec "$HOST_UID:$HOST_GID" "$@"
