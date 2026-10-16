# 図のレンダラー(#1828)。ビルドコンテキストはリポジトリルート:
#   docker build -f plugins/diagram-renderer/Dockerfile -t lets-blog-diagram-renderer .
# compose へは同梱しない。起動手順は docs/plugins/diagram-renderer.md。
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# ルートのマルチプロジェクト Gradle ビルドの一部としてビルドする。
# settings.gradle は他のサブプロジェクトも include するので、build.gradle だけを置いて構成を通す。
COPY gradlew settings.gradle build.gradle ./
COPY config ./config
COPY gradle ./gradle
RUN chmod +x gradlew
COPY packages/lbs-common/build.gradle ./packages/lbs-common/build.gradle
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
COPY plugins/diagram-renderer ./plugins/diagram-renderer

RUN ./gradlew :plugins:diagram-renderer:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/plugins/diagram-renderer/build/install/diagram-renderer ./
# PORT(既定 8080)、PLANTUML_BASE_URL(既定 http://localhost:8081)、PLANTUML_REQUEST_TIMEOUT_SECONDS(既定 30)、
# RENDER_MAX_BODY_BYTES(既定 10485760)で調整する。秘密は持たない(中核が送る Authorization を PlantUML サーバーへ転送する)。
EXPOSE 8080
ENTRYPOINT ["/app/bin/diagram-renderer"]
