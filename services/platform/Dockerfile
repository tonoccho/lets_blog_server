FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# ルートのマルチプロジェクトGradleビルドの一部としてビルドするため、
# ビルドコンテキストはリポジトリルート。
COPY gradlew settings.gradle build.gradle checkstyle.xml dependency-check-suppression.xml ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version

COPY libs ./libs
COPY services/platform ./services/platform
COPY services/legacy-api/build.gradle ./services/legacy-api/build.gradle
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
# postgresql-client: Keycloak PostgreSQL(keycloak-postgres)のバックアップ/リストア用
# (pg_dump/pg_restoreコマンド、BackupServiceが利用。issue #694で新規追加)。
# nodejs/npm: VSCode拡張機能(.vsix)のオンデマンドビルド用(システム画面からのダウンロード機能、
# VscodeExtensionBuildServiceが利用。legacy-apiのDockerfileと同じ理由、issue #696、C10-4で
# VscodeExtensionBuildServiceと共に移設)。
RUN apt-get update && apt-get install -y --no-install-recommends curl default-mysql-client postgresql-client nodejs npm \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/services/platform/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
