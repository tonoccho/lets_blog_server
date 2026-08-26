FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# ルートのマルチプロジェクトGradleビルドの一部としてビルドするため、
# ビルドコンテキストはリポジトリルート。
COPY gradlew settings.gradle build.gradle checkstyle.xml dependency-check-suppression.xml ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version

COPY libs ./libs
COPY services/media ./services/media
COPY services/legacy-api/build.gradle ./services/legacy-api/build.gradle
COPY services/log-writer/build.gradle ./services/log-writer/build.gradle
COPY services/gateway/build.gradle ./services/gateway/build.gradle
COPY services/identity/build.gradle ./services/identity/build.gradle
COPY services/ai/build.gradle ./services/ai/build.gradle
COPY services/content/build.gradle ./services/content/build.gradle
COPY services/analytics/build.gradle ./services/analytics/build.gradle
COPY services/project/build.gradle ./services/project/build.gradle

RUN ./gradlew :services:media:bootJar --no-daemon
# app.jarはSpring Bootのfat jar(依存jarがBOOT-INF/lib配下にネストされる)のため、
# `java -cp app.jar com.microsoft.playwright.CLI ...` では依存クラスを解決できない。
# ブラウザインストールCLIの実行だけのために、実行時クラスパスのjarをフラットに展開しておく。
RUN ./gradlew :services:media:copyRuntimeLibs --no-daemon

FROM eclipse-temurin:21-jre
# curl: docker-compose healthcheckがactuatorの/actuator/healthを叩くために使用。
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/services/media/build/libs/*.jar app.jar
# [recharts]組み込みタグのサーバーサイドレンダリング(RechartsRenderer)用にChromiumと、
# それが動作するために必要なOS共有ライブラリ(--with-deps)をインストールする
# (legacy-apiのDockerfileと同じ理由)。
COPY --from=build /workspace/services/media/build/runtime-libs ./runtime-libs
RUN apt-get update \
    && java -cp "runtime-libs/*" com.microsoft.playwright.CLI install --with-deps chromium \
    && rm -rf ./runtime-libs /var/lib/apt/lists/*
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
