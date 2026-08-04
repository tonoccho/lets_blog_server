FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version

COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
# openssh-client: WordPress SSHトランスポート用(phase10)。nodejs/npm: VSCode拡張機能(.vsix)の
# オンデマンドビルド用(システム画面からのダウンロード機能、VscodeExtensionBuildServiceが利用)。
# default-mysql-client: DBバックアップ/リストア用(mysqldump/mysqlコマンド、BackupServiceが利用)。
# Debian/Ubuntu系のdefault-mysql-clientはMariaDBクライアント実体だが、MySQL 8.0サーバーに対する
# mysqldump/mysqlの基本的な読み書きには互換性がある。
RUN apt-get update && apt-get install -y --no-install-recommends openssh-client nodejs npm default-mysql-client \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
