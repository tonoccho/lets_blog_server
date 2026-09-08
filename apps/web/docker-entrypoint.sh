#!/bin/sh
# docker-compose.ymlのwebサービスは ./apps/web:/app をバインドマウントするため、
# Dockerfileの `RUN npm ci` がイメージ内に作った /app/node_modules はマウントで
# 覆い隠される。クローン直後などホストに apps/web/node_modules が無い状態では、
# コンテナ内(node:22-alpine、musl)で改めて npm ci する必要がある(#1050)。
#
# ホストのNodeとコンテナのNode(musl)のネイティブバイナリ非互換を避けるため、
# インストールは常にこのコンテナ内で行う。生成物はrootが作るため、バインドマウント元の
# 所有者(=ホストの実行ユーザー)へchownし直す(root所有のままだと#1042と同種のEACCESを生む)。
set -e

APP_DIR=/app

if [ ! -x "$APP_DIR/node_modules/.bin/next" ]; then
  HOST_UID="$(stat -c '%u' "$APP_DIR")"
  HOST_GID="$(stat -c '%g' "$APP_DIR")"
  ( cd "$APP_DIR" && npm ci --legacy-peer-deps )
  chown -R "$HOST_UID:$HOST_GID" "$APP_DIR/node_modules"
fi

exec "$@"
