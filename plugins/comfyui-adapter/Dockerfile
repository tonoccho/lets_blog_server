# ComfyUI アダプター(#1813)。ビルドコンテキストはリポジトリルート:
#   docker build -f plugins/comfyui-adapter/Dockerfile -t lets-blog-comfyui-adapter .
# compose へは同梱しない。起動手順は docs/plugins/comfyui-adapter.md。
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
COPY plugins/comfyui-adapter ./plugins/comfyui-adapter

RUN ./gradlew :plugins:comfyui-adapter:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/plugins/comfyui-adapter/build/install/comfyui-adapter ./
# PORT(既定 8080)、COMFYUI_BASE_URL(既定 http://127.0.0.1:8188)、COMFYUI_CHECKPOINT、
# COMFYUI_POLL_INTERVAL_MILLIS(既定 1000)で調整する。秘密は持たない。
EXPOSE 8080
ENTRYPOINT ["/app/bin/comfyui-adapter"]
