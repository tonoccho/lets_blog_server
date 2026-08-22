# 01. アーキテクチャ移行方針の ADR 整備

Issue: [#552](https://github.com/tonoccho/lets_blog_server/issues/552)

## 内容

`docs/adr/` を新設し、Epic #551 で確定済みの4つの意思決定を ADR (Architecture Decision
Record) として記録した。各 ADR には Context / Decision / Consequences /
Alternatives considered を記載し、却下した代替案(モジュラーモノリス・自前JWT発行・
段階移行・共有スキーマ維持)とその理由も明記している。

## 成果物

- [docs/adr/README.md](../../docs/adr/README.md) — ADRの書式・運用ルール
- [docs/adr/0001-domain-based-microservices.md](../../docs/adr/0001-domain-based-microservices.md)
- [docs/adr/0002-keycloak-oidc.md](../../docs/adr/0002-keycloak-oidc.md)
- [docs/adr/0003-cutover-migration.md](../../docs/adr/0003-cutover-migration.md)
- [docs/adr/0004-schema-per-service.md](../../docs/adr/0004-schema-per-service.md)

以降のすべてのPhase 17〜19タスクは、これらのADRで確定した方針に従う。
