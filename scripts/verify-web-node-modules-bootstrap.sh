#!/bin/bash
# apps/web の Dockerイメージを、node_modulesの無い一時ディレクトリにバインドマウントして
# 単体で起動し、クローン直後の状態から web が healthy になり、生成された node_modules が
# ホストの実行ユーザー所有になることを検証する(#1050)。
#
# 非破壊: 稼働中の docker compose スタック(lbs-*)には一切触れない。専用の一時ディレクトリと
# `docker build` / `docker run` だけを使う。
#
# 使い方:
#   ./scripts/verify-web-node-modules-bootstrap.sh
#
# 終了コード: 0 = node_modulesが無い状態からnext devが3000番で応答し、
#             生成されたnode_modulesの所有者がホストの実行ユーザーと一致 / 1 = それ以外
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
IMAGE_TAG="lbs-web-bootstrap-verify:tmp"
CONTAINER_NAME="lbs-web-bootstrap-verify"

TMP_APP_DIR="$(mktemp -d)"

cleanup() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
  rm -rf "$TMP_APP_DIR"
}
trap cleanup EXIT

echo "--- 1/4: apps/web イメージをビルド ---"
docker build -t "$IMAGE_TAG" "$REPO_ROOT/apps/web"

echo "--- 2/4: クローン直後を模した node_modules の無い /app を用意 ---"
cp -r "$REPO_ROOT/apps/web/." "$TMP_APP_DIR/"
rm -rf "${TMP_APP_DIR:?}/node_modules"

echo "--- 3/4: コンテナを単体起動し、next dev が3000番で応答するまで待機 ---"
docker run -d --name "$CONTAINER_NAME" \
  -v "$TMP_APP_DIR:/app" \
  -e NEXTAUTH_SECRET=dummy-secret-for-verification \
  -e NEXTAUTH_URL=http://127.0.0.1:3000 \
  -e LETS_BLOG_GATEWAY_URL=http://127.0.0.1:0 \
  -e KEYCLOAK_WEB_CLIENT_SECRET=dummy-secret-for-verification \
  "$IMAGE_TAG" sh -c "cd /app && exec npm run dev" >/dev/null

OK=0
for i in $(seq 1 60); do
  if docker exec "$CONTAINER_NAME" \
      wget -q -O /dev/null http://127.0.0.1:3000/api/auth/csrf 2>/dev/null; then
    OK=1
    break
  fi
  sleep 5
done

echo "--- 4/4: 検証 ---"
if [ "$OK" -ne 1 ]; then
  echo "NG: next dev が3000番で応答しなかった。ログ:"
  docker logs "$CONTAINER_NAME" || true
  exit 1
fi

if [ ! -x "$TMP_APP_DIR/node_modules/.bin/next" ]; then
  echo "NG: $TMP_APP_DIR/node_modules/.bin/next が生成されていない"
  exit 1
fi

OWNER_UID="$(stat -c '%u' "$TMP_APP_DIR/node_modules")"
OWNER_GID="$(stat -c '%g' "$TMP_APP_DIR/node_modules")"
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

if [ "$OWNER_UID" != "$HOST_UID" ] || [ "$OWNER_GID" != "$HOST_GID" ]; then
  echo "NG: node_modules の所有者が ${OWNER_UID}:${OWNER_GID}" \
    "(期待値 ${HOST_UID}:${HOST_GID}=ホストの実行ユーザー)"
  exit 1
fi

echo "OK: node_modulesが無い状態からwebがhealthyになり、" \
  "生成されたnode_modulesの所有者もホストの実行ユーザー(${HOST_UID}:${HOST_GID})と一致した"
