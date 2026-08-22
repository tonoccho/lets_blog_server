FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# ルートのマルチプロジェクトGradleビルドの一部としてビルドするため、
# ビルドコンテキストはリポジトリルート。
COPY gradlew settings.gradle build.gradle checkstyle.xml dependency-check-suppression.xml ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version

COPY libs ./libs
COPY services/log-writer ./services/log-writer
COPY services/legacy-api/build.gradle ./services/legacy-api/build.gradle

RUN ./gradlew :services:log-writer:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/log-writer/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
