# Database Migration Testing Guide

## 前提: サービスごとに独立したマイグレーション

[ADR-0004](adr/0004-schema-per-service.md)(#570)により、DB スキーマはサービスごとに分かれている。
**Flyway マイグレーションもサービスごとに独立**しており、サービス間に適用順序の依存は無い。

| サービス | スキーマ | マイグレーション |
|---|---|---|
| identity | `lbs_identity` | `services/identity/src/main/resources/db/migration/` |
| project | `lbs_project` | `services/project/src/main/resources/db/migration/` |
| content | `lbs_content` | `services/content/src/main/resources/db/migration/` |
| media | `lbs_media` | `services/media/src/main/resources/db/migration/` |
| ai | `lbs_ai` | `services/ai/src/main/resources/db/migration/` |
| analytics | `lbs_analytics` | `services/analytics/src/main/resources/db/migration/` |
| publishing | `lbs_publishing` | `services/publishing/src/main/resources/db/migration/` |
| platform | `lbs_platform` | `services/platform/src/main/resources/db/migration/` |
| log-writer | `lbs_log` | `services/log-writer/src/main/resources/db/migration/` |

分割前の単一サービス `legacy-api` とその `lets_blog` スキーマは、#583 / #785 で削除済み。
既存データの移行手順は [docs/SERVICE_SCHEMA_MIGRATION.md](SERVICE_SCHEMA_MIGRATION.md) を参照。

## 何が検証されているか

### 1. スキーマとエンティティの整合(コンテキスト起動そのもの)

各サービスの `src/test/resources/application-test.yml` は
`spring.flyway.enabled: true` + `spring.jpa.hibernate.ddl-auto: validate` になっている。

つまり `@SpringBootTest` が起動する時点で、**マイグレーションが作ったスキーマと JPA
エンティティ定義の一致**が検証される。食い違えば `SchemaManagementException`
(`missing table` / `missing column`)で context load が落ちる。

これは #886(identity-service が Flyway 依存の追加漏れで「missing table [role_permissions]」を出して
起動できなかった)と同じ失敗モードを、テストで先に捕まえるための仕掛けである。

### 2. 冪等性・履歴・チェックサム(`MigrationContractTest`)

各サービスに `MigrationContractTest` があり、共通実装
`packages/lbs-common/src/testFixtures/java/com/letsblog/common/testfixtures/MigrationContract.java`
(#914)を呼ぶ。

| 検証 | 内容 |
|---|---|
| `verifyIdempotent` | 2回目の `migrate()` が**成功するだけでなく実行件数0**であること |
| `verifyAllMigrationsApplied` | 全マイグレーションが `SUCCESS` で、version/description/installedOn が履歴に残っていること |
| `verifyValidates` | 適用済みマイグレーションのチェックサムがファイルと一致すること |

冪等性を「失敗しない」ではなく「**0件である**」で見ているのは、再適用で余計な行を足したり
DDL を二重に流したりしても「失敗しない」だけなら通ってしまうため。

## 実行方法

テストは実 MySQL に接続する(ADR-0006: Testcontainers は使わない)。前提の整え方は
[docs/TEST_DOCUMENTATION.md](TEST_DOCUMENTATION.md) の「テスト用MySQLの前提」を参照。

```bash
# 前提: 開発スタックの MySQL を 127.0.0.1:3306 へ公開しておく
docker compose -f docker-compose.yml -f docker-compose.host-tests.yml up -d mysql
bash scripts/check-test-db.sh

# 1サービスのマイグレーション契約だけ
./gradlew :services:content:test --tests "*MigrationContractTest*"

# 全サービス
./gradlew test
```

CI は無いため、`./gradlew test` をローカルで実行したときに一緒に走る
(専用の実行経路は持たない)。

## 新しいマイグレーションを追加する

1. 対象サービスの `src/main/resources/db/migration/` に `V<n>__<description>.sql` を作る
   (バージョン番号はそのサービス内で連番。他サービスとは独立)
2. エンティティ側も合わせて変更する
3. `./gradlew :services:<name>:test` を実行する
   - スキーマとエンティティが食い違っていれば context load が落ちる
   - 冪等でなければ `MigrationContractTest` が落ちる

### やってはいけないこと

- **適用済みのマイグレーションファイルを編集する。** チェックサムが変わり、本番は
  `FlywayValidateException` で起動できなくなる。必ず新しいバージョンを足す
  (`verifyValidates` がテストで先に落とす)
- **他サービスのスキーマを参照する。** ADR-0004 が禁じている。各サービスのDBユーザーは
  自分のスキーマにしか権限を持たないため、そもそも実行時に失敗する
- **クロススキーマの FOREIGN KEY を張る。** 同上。ID だけを保持し、削除時の連動は
  アプリケーション側(ドメインイベント)の責務とする

## トラブルシューティング

| 症状 | 原因 | 対処 |
|---|---|---|
| `Unknown database 'lbs_*_test'` | テストスキーマが未作成 | `docker compose exec mysql bash /docker-entrypoint-initdb.d/02-create-test-schemas.sh` |
| `Communications link failure` | ホストから MySQL へ到達できない(#762) | `docker compose -f docker-compose.yml -f docker-compose.host-tests.yml up -d mysql` |
| `missing table` / `missing column` | マイグレーションとエンティティの食い違い | どちらが正しいかを決め、マイグレーションを追加するかエンティティを直す |
| `FlywayValidateException` | 適用済みファイルを編集した | 編集を戻し、新しいバージョンとして追加する |
| 2回目の `migrate()` が0件でない | バージョン番号の重複、または冪等でないDDL | 番号を振り直すか、`IF NOT EXISTS` 等で冪等にする |

## 参考

- [ADR-0004: schema-per-service](adr/0004-schema-per-service.md)
- [ADR-0006: サービス別のテスト戦略](adr/0006-per-service-test-strategy.md)
- [docs/SERVICE_SCHEMA_MIGRATION.md](SERVICE_SCHEMA_MIGRATION.md) — スキーマ分離とデータ移行
- [docs/TEST_DOCUMENTATION.md](TEST_DOCUMENTATION.md) — テスト全般
