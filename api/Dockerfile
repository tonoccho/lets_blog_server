FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version

COPY src ./src
RUN ./gradlew bootJar --no-daemon
# app.jarはSpring Bootのfat jar(依存jarがBOOT-INF/lib配下にネストされる)のため、
# `java -cp app.jar com.microsoft.playwright.CLI ...` では依存クラスを解決できない。
# ブラウザインストールCLIの実行だけのために、実行時クラスパスのjarをフラットに展開しておく。
RUN ./gradlew copyRuntimeLibs --no-daemon

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
# [blogcard]/[amazon]組み込みタグのスクレイピング(ContentCacheService)用にChromiumと、
# それが動作するために必要なOS共有ライブラリ(--with-deps)をインストールする。
# 一時的にコピーしたruntime-libsはブラウザインストール後は不要なため、同じレイヤーで削除する
# (別レイヤーで消すとイメージサイズには残ってしまうため)。
COPY --from=build /workspace/build/runtime-libs ./runtime-libs
RUN apt-get update \
    && java -cp "runtime-libs/*" com.microsoft.playwright.CLI install --with-deps chromium \
    && rm -rf ./runtime-libs /var/lib/apt/lists/*
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
