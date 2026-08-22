# 02. Gradle マルチプロジェクト構成への再編

Issue: [#553](https://github.com/tonoccho/lets_blog_server/issues/553)

## 内容

`api` / `log-writer` はそれぞれ独立したGradleビルド(独自の`gradlew`/`settings.gradle`)
だったため、複数サービスと共通ライブラリを1つのビルドで扱えるようルートのマルチプロジェクト
構成へ再編した。コードの移動(パッケージ変更等)は行わず、器の再配置のみ。

## 変更内容

- ルートに `settings.gradle` / `build.gradle` / 単一の `gradlew` を新設
- `api/` → `services/legacy-api/`、`log-writer/` → `services/log-writer/` へ移動
- `libs/lbs-common/` の空モジュールを新設(中身は[03](03-lbs-common.md)で追加)
- checkstyle / jacoco / dependencyCheck の設定をルートの `subprojects {}` ブロックで
  一元化(各サービスがそのプラグインを適用している場合のみ設定が効く形にし、
  未適用のサービスに新しい振る舞いを強制しない)
- 両サービスのDockerfileをリポジトリルートをビルドコンテキストとする形に変更
  (マルチプロジェクトビルド全体が必要なため)
- `.gitignore`/`.dockerignore` に `build/` `.gradle/` `bin/` を追加
  (追加した経緯: Dockerビルドコンテキストにホスト側のGradleビルド成果物が混入し、
  ソース変更がイメージに反映されない不具合を[05](05-docker-compose.md)の作業中に発見・修正)

## 検証

- `./gradlew lint test` がルートから全サブプロジェクトに対して実行できることを確認
- `docker compose build api log-writer` が新しいビルドコンテキストで成功することを確認
