FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# ルートのマルチプロジェクトGradleビルドの一部としてビルドするため、
# ビルドコンテキストはリポジトリルート。
COPY gradlew settings.gradle build.gradle checkstyle.xml dependency-check-suppression.xml ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version

COPY libs ./libs
COPY services/ai ./services/ai
COPY services/legacy-api/build.gradle ./services/legacy-api/build.gradle
COPY services/log-writer/build.gradle ./services/log-writer/build.gradle
COPY services/gateway/build.gradle ./services/gateway/build.gradle
COPY services/identity/build.gradle ./services/identity/build.gradle
COPY services/media/build.gradle ./services/media/build.gradle
COPY services/content/build.gradle ./services/content/build.gradle
COPY services/analytics/build.gradle ./services/analytics/build.gradle
COPY services/project/build.gradle ./services/project/build.gradle
COPY services/platform/build.gradle ./services/platform/build.gradle

RUN ./gradlew :services:ai:bootJar --no-daemon

FROM eclipse-temurin:21-jre
# curl: docker-compose healthcheckがactuatorの/actuator/healthを叩くために使用。
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/services/ai/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
