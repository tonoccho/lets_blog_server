# Phase 17: 基盤整備

## 目的

[Epic #551](https://github.com/tonoccho/lets_blog_server/issues/551) で決定した
アーキテクチャ刷新(ドメイン単位のフルマイクロサービス化 + Keycloak認証基盤への一括切り替え)を
実行するにあたり、Phase 18(認証・認可)・Phase 19(ドメイン分割)以降の各サービス抽出Issueが
土台にできる「器」を先に用意する。この Phase では実際のサービス(gateway, identity-service等)は
まだ作らない — Gradleビルド構成・共通ライブラリ・スキーマ分離の枠組み・Docker Compose・
API クライアント生成・CI・ドキュメントの土台整備のみを行う。

## 決定済み事項

Phase 17 全体を貫く方針は [docs/adr/](../../docs/adr/README.md) に記録済み。

| ADR | 決定内容 |
|---|---|
| [ADR-0001](../../docs/adr/0001-domain-based-microservices.md) | ドメイン単位のフルマイクロサービス分割を採用する |
| [ADR-0002](../../docs/adr/0002-keycloak-oidc.md) | 認証基盤に Keycloak (OIDC) を採用する |
| [ADR-0003](../../docs/adr/0003-cutover-migration.md) | 旧認証機構は並行運用せず一括で切り替える |
| [ADR-0004](../../docs/adr/0004-schema-per-service.md) | サービスごとに MySQL スキーマを分離し、跨ぎ JOIN と FK を禁止する |

## タスク一覧

1. [01-adr](01-adr.md) — アーキテクチャ移行方針の ADR 整備(#552)
2. [02-gradle-multi-project](02-gradle-multi-project.md) — Gradle マルチプロジェクト構成への再編(#553)
3. [03-lbs-common](03-lbs-common.md) — 共通ライブラリ lbs-common の抽出(#554)
4. [04-api-client-generation](04-api-client-generation.md) — サービス別OpenAPI公開とorvalのマルチターゲット化(#555)
5. [05-docker-compose](05-docker-compose.md) — Docker Compose のマルチサービス構成への整備(#556)
6. [06-github-actions](06-github-actions.md) — GitHub Actions のマルチサービス構成への対応(#557)
7. [07-docs](07-docs.md) — ローカル開発手順とアーキテクチャドキュメントの更新(#558、本ドキュメント)

各タスクの実装は完了済み。実装の詳細・検証内容は各タスクファイルおよび対応する
GitHub Issue/PRを参照。C1([サービス別スキーマ分離](https://github.com/tonoccho/lets_blog_server/issues/570))は
Phase 19 に属する Issue だが、A2/A3 に依存するため実際にはこの Phase の直後に着手している。

## Phase 17 の成果物

- `settings.gradle` / `build.gradle`(ルート) — `libs/lbs-common`・`services/legacy-api`・
  `services/log-writer` を含むマルチプロジェクトビルド
- `libs/lbs-common` — 暗号化・エラー応答規約・ログメッセージ型・相関IDフィルタ・
  スキーマ移行ジョブ基盤
- `mysql/init/` — サービス別スキーマ/ユーザーの初期化スクリプト
- `docker-compose.yml` の YAMLアンカーによるテンプレート化 + 全サービスのヘルスチェック
- `orval.config.js` のマルチターゲット化
- `.github/workflows/api-services-test.yml` のサービス単位マトリクスビルド
- `docs/adr/`, `docs/SERVICE_SCHEMA_MIGRATION.md`, `docs/DOCKER_COMPOSE_ARCHITECTURE.md`,
  `spec/phase17/`(本ディレクトリ)

## 次のPhase

- Phase 18: 認証・認可(Keycloak導入、api-gateway/identity-service新設、既存クライアントの移行)
- Phase 19: ドメイン分割(スキーマ分離の実データ移行、各ドメインサービスの抽出)
