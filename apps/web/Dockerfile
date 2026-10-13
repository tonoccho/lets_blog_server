# 鮮度確認(scripts/check-image-freshness.py, #1686)が読む、ソース内容のスタンプ。
# ビルド本体とは独立したステージで、ここの COPY はソースパス表(SERVICE_SOURCE_PATHS['web'])と
# 同じ集合にすること(単体テスト DockerfileStampStages が照合する)。最終イメージの末尾に
# 1層足すだけなので、ビルドキャッシュを壊さない。
# 既定の web は本番ビルドで、`next build` が src / public / messages / 設定を焼き込む(#1743)ため、
# それらもスタンプの対象にする(e2e や jest 設定など、実行に効かないものは含めない)。
FROM node:22-alpine AS stamp
WORKDIR /ctx
COPY package.json package-lock.json Dockerfile docker-entrypoint.sh next.config.ts tsconfig.json postcss.config.mjs ./
COPY src ./src
COPY public ./public
COPY messages ./messages
# 追跡されない node_modules(packages/ 配下の npm install 済み)は数えない。
RUN cd /ctx && find . -name node_modules -prune -o -type f -print | sed 's|^\./||' | LC_ALL=C sort \
    | while IFS= read -r f; do \
        printf '%s  %s\n' "$({ printf 'blob %s\0' "$(wc -c < "$f")"; cat "$f"; } | sha1sum | cut -d' ' -f1)" "$f"; \
    done | sha1sum | cut -d' ' -f1 > /lbs-source-stamp

FROM node:22-alpine
WORKDIR /app

# su-exec: entrypointがrootから権限を落として起動するために使う。本番モードでは非rootの
# node へ、開発モード(#1743 の docker-compose.dev-web.yml)ではバインドマウント元の
# 所有者(ホストの実行ユーザー)へ落とす(#1042)。
RUN apk add --no-cache su-exec

COPY package.json package-lock.json ./
RUN npm ci --legacy-peer-deps

COPY . .

# 本番ビルドをイメージのビルド時に済ませる(#1743)。既定の web は `next start` でこれを配信し、
# `next dev` のオンデマンドコンパイル・Turbopack キャッシュ・ファイル監視のメモリを持たない。
# NEXT_PUBLIC_* はビルド時にバンドルへ焼き込まれるが、compose の web には無い
# (scripts/test_compose_web_production.py が固定している)。実行時の設定はすべてサーバー側の
# 環境変数(LETS_BLOG_GATEWAY_URL 等)で、`next start` が実行時に読む。
RUN npm run build \
    && chown -R node:node .next

# NODE_ENV は npm ci / next build の後に設定する(先に設定すると devDependencies
# (tailwind / typescript)が入らず、ビルドが落ちる)。LBS_WEB_MODE は docker-entrypoint.sh が
# 本番(イメージ内の node_modules と .next をそのまま使う)か開発(バインドマウントの
# 初期化をする)かを分けるのに使う。
ENV NODE_ENV=production
ENV LBS_WEB_MODE=production

COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

# 鮮度確認用のソース内容スタンプ(#1686)。末尾に置き、上の層のキャッシュに影響させない。
COPY --from=stamp /lbs-source-stamp /etc/lbs-source-stamp

EXPOSE 3000
ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["npm", "run", "start"]
