FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# ルートのマルチプロジェクトGradleビルドの一部としてビルドするため、
# ビルドコンテキストはリポジトリルート。
COPY gradlew settings.gradle build.gradle checkstyle.xml dependency-check-suppression.xml ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version

COPY libs ./libs
COPY services/legacy-api ./services/legacy-api
COPY services/log-writer/build.gradle ./services/log-writer/build.gradle
COPY services/gateway/build.gradle ./services/gateway/build.gradle
COPY services/identity/build.gradle ./services/identity/build.gradle
COPY services/media/build.gradle ./services/media/build.gradle
COPY services/ai/build.gradle ./services/ai/build.gradle
COPY services/content/build.gradle ./services/content/build.gradle
COPY services/analytics/build.gradle ./services/analytics/build.gradle
COPY services/project/build.gradle ./services/project/build.gradle
COPY services/platform/build.gradle ./services/platform/build.gradle
COPY services/publishing/build.gradle ./services/publishing/build.gradle

RUN ./gradlew :services:legacy-api:bootJar --no-daemon

FROM eclipse-temurin:21-jre
# curl: docker-compose healthcheckがactuatorの/actuator/healthを叩くために使用(#556)。
# (openssh-clientは、それを使う唯一のクラスSshKeyGenerationServiceの削除に伴い外した。
# issue #822。実装は#577でproject-serviceへ移設済みで、legacy-api側は呼び出し元ゼロのまま
# 残っていた。WordPressのSSHトランスポート自体もproject-serviceのSiteServiceが担う。
# services/project/Dockerfile参照)。
# (DBバックアップ/リストア用のdefault-mysql-clientは、BackupServiceと共にplatform-serviceへ
# 移設した。issue #694、C10-2。services/platform/Dockerfile参照。nodejs/npmは、VSCode拡張機能
# (.vsix)のオンデマンドビルド用に使っていた唯一のクラスVscodeExtensionBuildServiceと共に
# platform-serviceへ移設した。issue #696、C10-4。services/platform/Dockerfile参照)。
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/services/legacy-api/build/libs/*.jar app.jar
# [blogcard]/[amazon]組み込みタグのスクレイピング・記事プレビューのテーマ骨格取得(Playwright/
# Chromium)はcontent-serviceへ移設した(issue #576)。legacy-apiはもうヘッドレスブラウザを
# 必要としない。
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
