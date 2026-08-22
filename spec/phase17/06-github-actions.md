# 06. GitHub Actions のマルチサービス構成への対応

Issue: [#557](https://github.com/tonoccho/lets_blog_server/issues/557)

## 内容

CIのパスフィルタが`api/**`前提のままだったため(#553でサービスが`services/`配下へ
移動済みにもかかわらず追随できておらず、実質的に壊れていた)、マルチプロジェクト
Gradleビルドに合わせてサービス単位のマトリクスビルドへ再構成した。
**GitHub Actions自体は本Issue時点で意図的に無効化されており、有効化の是非は
スコープ外**(定義の更新のみを行う)。

## 変更内容

- `api-test.yml` + `log-writer-test.yml` を `api-services-test.yml` へ統合。
  `dorny/paths-filter` による変更検出 → `service: [lbs-common, legacy-api, log-writer]`
  マトリクス。`libs/**`(または ルートのGradleファイル)の変更は全サービスの
  ジョブをトリガーする
- `log-writer` / `lbs-common` に `jacoco` プラグインを追加し、Codecovへ
  サービス別flag(`legacy-api` / `log-writer` / `lbs-common`)でアップロード
- `migration-test.yml` / `performance-testing.yml` に残っていた `api/` 直下参照
  (`cd api`、`api/gradlew`等)を `services/legacy-api` + ルートgradlewへ修正
- READMEバッジ・`CLAUDE.md`「19. GitHub Actions CI/CD Workflows」を更新

## 新規サービス追加時の手順

`api-services-test.yml`の`service:`マトリクスと`dorny/paths-filter`のfiltersに
1エントリ追加する。共有パス(`libs/**`等)を含めれば、lbs-common変更時に
全サービストリガーする挙動を自動的に引き継ぐ。

## 検証

- 全ワークフローYAMLの構文検証
- `./gradlew lint test` が全サブプロジェクトで成功し、
  `libs/lbs-common`・`services/log-writer`が新たにjacocoレポートを生成することを確認
- 実際のCI実行(Actions自体が無効化されているため)は行っていない
