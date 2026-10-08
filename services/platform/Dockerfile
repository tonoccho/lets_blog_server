# 鮮度確認(scripts/check-image-freshness.py, #1686)が読む、ソース内容のスタンプ。
# ビルド本体とは独立したステージで、ここの COPY は上のビルドステージの COPY(ソースパス表)と
# 同じ集合にすること(単体テスト DockerfileStampStages が照合する)。最終イメージの末尾に
# 1層足すだけなので、ビルドキャッシュを壊さない。
FROM eclipse-temurin:21-jre AS stamp
WORKDIR /ctx
COPY gradlew settings.gradle build.gradle ./
COPY config ./config
COPY gradle ./gradle
COPY packages ./packages
COPY services/platform ./services/platform
COPY services/log-writer/build.gradle ./services/log-writer/build.gradle
COPY services/gateway/build.gradle ./services/gateway/build.gradle
COPY services/identity/build.gradle ./services/identity/build.gradle
COPY services/media/build.gradle ./services/media/build.gradle
COPY services/ai/build.gradle ./services/ai/build.gradle
COPY services/content/build.gradle ./services/content/build.gradle
COPY services/analytics/build.gradle ./services/analytics/build.gradle
COPY services/project/build.gradle ./services/project/build.gradle
COPY services/publishing/build.gradle ./services/publishing/build.gradle
# 追跡されない node_modules(packages/ 配下の npm install 済み)は数えない。
RUN cd /ctx && find . -name node_modules -prune -o -type f -print | sed 's|^\./||' | LC_ALL=C sort \
    | while IFS= read -r f; do \
        printf '%s  %s\n' "$({ printf 'blob %s\0' "$(wc -c < "$f")"; cat "$f"; } | sha1sum | cut -d' ' -f1)" "$f"; \
    done | sha1sum | cut -d' ' -f1 > /lbs-source-stamp

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# ルートのマルチプロジェクトGradleビルドの一部としてビルドするため、
# ビルドコンテキストはリポジトリルート。
COPY gradlew settings.gradle build.gradle ./
COPY config ./config
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version

COPY packages ./packages
COPY services/platform ./services/platform
COPY services/log-writer/build.gradle ./services/log-writer/build.gradle
COPY services/gateway/build.gradle ./services/gateway/build.gradle
COPY services/identity/build.gradle ./services/identity/build.gradle
COPY services/media/build.gradle ./services/media/build.gradle
COPY services/ai/build.gradle ./services/ai/build.gradle
COPY services/content/build.gradle ./services/content/build.gradle
COPY services/analytics/build.gradle ./services/analytics/build.gradle
COPY services/project/build.gradle ./services/project/build.gradle
COPY services/publishing/build.gradle ./services/publishing/build.gradle

RUN ./gradlew :services:platform:bootJar --no-daemon

FROM eclipse-temurin:21-jre
# curl: docker-compose healthcheckがactuatorの/actuator/healthを叩くために使用。
# default-mysql-client: 全サービスMySQLスキーマのバックアップ/リストア用(mysqldump/mysqlコマンド、
# BackupServiceが利用。legacy-apiのDockerfileと同じ理由、issue #694でBackupServiceと共に移設)。
# Debian/Ubuntu系のdefault-mysql-clientはMariaDBクライアント実体だが、MySQL 8.0サーバーに対する
# mysqldump/mysqlの基本的な読み書きには互換性がある。
# postgresql-client-15: Keycloak PostgreSQL(keycloak-postgres)のバックアップ/リストア用
# (pg_dump/pg_restoreコマンド、BackupServiceが利用。issue #694で新規追加)。
# メジャーバージョンを15に固定してPGDG(PostgreSQL公式apt リポジトリ)からインストールする
# (汎用の`postgresql-client`ではベースイメージのUbuntuリポジトリからv18が入ってしまう)。
# 理由(issue #1141): pg_restore v18はリストア時に無条件で`SET transaction_timeout = 0;`を
# 発行するが、これはPostgreSQL 17以降で追加されたGUCで、実際にリストア対象とする
# keycloak-postgres(docker-compose.ymlでpostgres:15に固定)はこれを認識せずエラーになる。
# keycloak-postgres自体のバージョンを上げるのは本番相当の影響範囲精査が要る別判断のため、
# ここではクライアント側をサーバーのメジャーバージョン(15)に合わせて固定する。
# nodejs/npm: VSCode拡張機能(.vsix)のオンデマンドビルド用(システム画面からのダウンロード機能、
# VscodeExtensionBuildServiceが利用。legacy-apiのDockerfileと同じ理由、issue #696、C10-4で
# VscodeExtensionBuildServiceと共に移設)。
RUN apt-get update && apt-get install -y --no-install-recommends curl default-mysql-client nodejs npm gnupg ca-certificates \
    && install -d /usr/share/postgresql-common/pgdg \
    && curl -sf -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc https://www.postgresql.org/media/keys/ACCC4CF8.asc \
    && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt $(. /etc/os-release && echo $VERSION_CODENAME)-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
    && apt-get update && apt-get install -y --no-install-recommends postgresql-client-15 \
    && apt-get purge -y --auto-remove gnupg \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/services/platform/build/libs/*.jar app.jar
# 鮮度確認用のソース内容スタンプ(#1686)。末尾に置き、上の層のキャッシュに影響させない。
COPY --from=stamp /lbs-source-stamp /etc/lbs-source-stamp
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
