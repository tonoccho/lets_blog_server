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
COPY services/publishing ./services/publishing
COPY infra/wordpress/letsblog-plugin ./infra/wordpress/letsblog-plugin
COPY services/log-writer/build.gradle ./services/log-writer/build.gradle
COPY services/gateway/build.gradle ./services/gateway/build.gradle
COPY services/identity/build.gradle ./services/identity/build.gradle
COPY services/media/build.gradle ./services/media/build.gradle
COPY services/ai/build.gradle ./services/ai/build.gradle
COPY services/content/build.gradle ./services/content/build.gradle
COPY services/analytics/build.gradle ./services/analytics/build.gradle
COPY services/project/build.gradle ./services/project/build.gradle
COPY services/platform/build.gradle ./services/platform/build.gradle
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
COPY services/publishing ./services/publishing
# SSHサイトへ配置するletsblogプラグイン(processResourcesが取り込む。issue #1556)。
COPY infra/wordpress/letsblog-plugin ./infra/wordpress/letsblog-plugin
COPY services/log-writer/build.gradle ./services/log-writer/build.gradle
COPY services/gateway/build.gradle ./services/gateway/build.gradle
COPY services/identity/build.gradle ./services/identity/build.gradle
COPY services/media/build.gradle ./services/media/build.gradle
COPY services/ai/build.gradle ./services/ai/build.gradle
COPY services/content/build.gradle ./services/content/build.gradle
COPY services/analytics/build.gradle ./services/analytics/build.gradle
COPY services/project/build.gradle ./services/project/build.gradle
COPY services/platform/build.gradle ./services/platform/build.gradle

RUN ./gradlew :services:publishing:bootJar --no-daemon

FROM eclipse-temurin:21-jre
# openssh-client: WordPress SSHトランスポート用(legacy-apiのcms/sshパッケージから移設。issue #707)。
# curl: docker-compose healthcheckがactuatorの/actuator/healthを叩くために使用。
RUN apt-get update && apt-get install -y --no-install-recommends openssh-client curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/services/publishing/build/libs/*.jar app.jar
# 鮮度確認用のソース内容スタンプ(#1686)。末尾に置き、上の層のキャッシュに影響させない。
COPY --from=stamp /lbs-source-stamp /etc/lbs-source-stamp
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
