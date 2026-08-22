FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# ルートのマルチプロジェクトGradleビルドの一部としてビルドするため、
# ビルドコンテキストはリポジトリルート。
COPY gradlew settings.gradle build.gradle checkstyle.xml dependency-check-suppression.xml ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version

COPY libs ./libs
COPY services/gateway ./services/gateway
COPY services/legacy-api/build.gradle ./services/legacy-api/build.gradle
COPY services/log-writer/build.gradle ./services/log-writer/build.gradle

RUN ./gradlew :services:gateway:bootJar --no-daemon

FROM eclipse-temurin:21-jre
# curl: docker-compose healthcheckがactuatorの/actuator/healthを叩くために使用。
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/services/gateway/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
