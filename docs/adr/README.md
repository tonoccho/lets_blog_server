# Architecture Decision Records (ADR)

このディレクトリには、本プロジェクトのアーキテクチャに関する重要な意思決定を記録する。

## 目的

GitHub Issue は実装計画・仕様であり「何を作るか」を記述する。
一方 ADR は「なぜその設計を選んだか」「他にどんな案を検討し、なぜ却下したか」という
意思決定の背景と根拠を後から辿れるようにするためのものである。

> かつては `spec/` 配下に仕様書を置いていたが、commit 49183b8 で削除された。
> 現在の一次情報は GitHub Issue と `docs/` である(issue #590)。

## 書式

各 ADR は `docs/adr/NNNN-title.md` というファイル名で作成する（`NNNN` は4桁の連番）。

ADR には以下のセクションを含める。

- **Status**: `Proposed` / `Accepted` / `Deprecated` / `Superseded by ADR-XXXX` のいずれか
- **Context**: どのような問題・制約があり、この決定が必要になったか
- **Decision**: 何を決定したか
- **Consequences**: この決定によって生じる利点・欠点・トレードオフ
- **Alternatives considered**: 検討した他の選択肢と、それぞれを却下した理由

## 運用ルール

- ADR は一度 `Accepted` になったら本文を書き換えない。決定を覆す場合は新しい ADR を作成し、
  古い ADR の Status を `Superseded by ADR-XXXX` に更新する。
- 番号は追加のみで、欠番・再利用はしない。
- 実装の詳細（コード構成、API仕様など）は Issue と `docs/` に記載し、ADR には書かない。

## 一覧

| ADR | タイトル |
|---|---|
| [ADR-0001](0001-domain-based-microservices.md) | ドメイン単位のフルマイクロサービス分割を採用する |
| [ADR-0002](0002-keycloak-oidc.md) | 認証基盤に Keycloak (OIDC) を採用する |
| [ADR-0003](0003-cutover-migration.md) | 旧認証機構は並行運用せず一括で切り替える |
| [ADR-0004](0004-schema-per-service.md) | サービスごとに MySQL スキーマを分離し、跨ぎ JOIN と FK を禁止する |
| [ADR-0005](0005-service-to-service-client-credentials.md) | サービス間通信は Client Credentials によるサービストークン＋元ユーザーIDヘッダーで認証する |
| [ADR-0006](0006-per-service-test-strategy.md) | サービス別のテスト戦略(DB・JWTフィクスチャ・契約テスト・モック方針)を確立する |
| [ADR-0007](0007-web-vscode-duplicate-feature-triage.md) | Web管理画面とVSCode拡張の重複候補機能は機能ごとに判断し、一律の統合はしない |
| [ADR-0008](0008-auth-gate-in-each-service-security-config.md) | 認証ゲートは各サービス自身の SecurityConfig で担い、gateway では実施しない |
| [ADR-0009](0009-sdk-api-client-not-consumed-by-web.md) | sdk/api-client は web から利用せず、生成物の維持のみを行う |
| [ADR-0010](0010-github-to-gitlab-migration.md) | 開発ワークフローを GitHub からセルフホスト GitLab CE へ移行する |
