FROM node:22-alpine
WORKDIR /app

# su-exec: entrypointがroot(npm ci/chown用)からホストの実行ユーザーへ権限を落として
# `next dev` を実行するために使う(#1042)。
RUN apk add --no-cache su-exec

COPY package.json package-lock.json ./
RUN npm ci --legacy-peer-deps

COPY . .

COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

EXPOSE 3000
ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["npm", "run", "dev"]
