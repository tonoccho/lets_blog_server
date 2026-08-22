# サービス別スキーマ分離とデータ移行ガイド

[ADR-0004](adr/0004-schema-per-service.md) に基づき、各サービスは同一MySQLインスタンス内で
独立したスキーマを持つ。本ドキュメントは、新しいサービス(project-service, content-service 等、
`spec` phase19以降で抽出される各サービス)を追加する際に従うべき規約と、既存データを
移行する手順をまとめる。

このIssue(#570)の時点では、以下のスキーマと専用ユーザーの「器」だけが用意されている。
実際にどのテーブルをどのスキーマへ移すかは、各サービス抽出Issue(C2, C4〜C10)で決定する。

## スキーマ一覧

| スキーマ | 用途 | 対応するサービス(将来) |
|---|---|---|
| `lbs_identity` | ユーザー・認証情報 | identity-service (#561) |
| `lbs_project` | サイト/プロジェクト設定 | project-service (#577) |
| `lbs_content` | 記事コンテンツ | content-service (#576) |
| `lbs_media` | 画像生成 | media-service (#573) |
| `lbs_ai` | AI執筆支援 | ai-service (#574) |
| `lbs_publishing` | WordPress発行 | publishing-service (#575) |
| `lbs_analytics` | GA/AdSense分析 | analytics-service (#578) |
| `lbs_platform` | バックアップ等の運用系 | platform-service (#579) |
| `lbs_log` | ログ | log-writer(既存。移行対象は#572で決定) |

`mysql/init/01-create-service-schemas.sh` が、MySQLコンテナの初回起動時
(データボリュームが空の場合のみ)にこれらのスキーマと、スキーマ名と同名のユーザー
(例: `lbs_identity`@`%`)を作成する。各ユーザーは自分のスキーマにしかアクセスできない
(`GRANT ALL PRIVILEGES ON <schema>.* TO '<schema>'@'%'`)。

**既存のMySQLデータボリュームがある環境では自動適用されない**(MySQL公式イメージの仕様)。
新規に反映するにはボリュームの再作成が必要(`docker compose down -v` 等。既存データを
消してよいことを確認してから実行すること)。

各ユーザーのパスワードは `.env` の `LBS_<SCHEMA>_DB_PASSWORD` 系変数で設定する
(`.env.example` 参照)。

## サービス跨ぎのJOIN/FKの禁止

ADR-0004により、スキーマを跨いだSQLのJOINと外部キー制約は禁止する。レビュー時は次の観点を確認する。

- 新しいマイグレーション(`V*.sql`)に、自スキーマ以外のテーブルへの `FOREIGN KEY` 制約がないか
- JPAエンティティに、他サービスの所有するテーブルへの `@ManyToOne` / `@OneToMany` 等の
  直接マッピングが追加されていないか(他サービスのデータはAPI呼び出しか
  イベント購読で取得する。C11/C12参照)
- リポジトリのクエリ(JPQL/ネイティブSQL)に、他スキーマのテーブル名を含むJOINがないか

## 新規サービスのFlyway設定

新しいサービスモジュールの `build.gradle` から、共通のFlyway依存関係テンプレートを読み込む。

```groovy
apply from: "${rootDir}/gradle/flyway-service.gradle"
```

`application.yml` 側は、サービスごとのスキーマ名・接続情報を次の形で設定する
(`lbs_project` の場合の例):

```yaml
spring:
  datasource:
    url: jdbc:mysql://mysql:3306/lbs_project?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
    username: lbs_project
    password: ${LBS_PROJECT_DB_PASSWORD}
  flyway:
    schemas: lbs_project
    locations: classpath:db/migration
```

既存のV1〜V63マイグレーション(`services/legacy-api/src/main/resources/db/migration/`)は
再配置しない。各サービスは自スキーマ用の新規の初期マイグレーション(`V1__init_schema.sql`)から
始める。

## 既存データの移行

`libs/lbs-common` の `com.letsblog.common.migration` パッケージに、既存スキーマから
サービス別スキーマへデータを移すワンショット移行ジョブの基盤(`SchemaMigrationJob`)がある。

- 冪等性: 同じジョブ名は、移行先スキーマの管理テーブル(`_migration_state`)にCOMPLETEDと
  記録された後は再実行されない。
- 再実行可能性: `MigrationBatchStep` の実装が「まだコピーしていない行から再開する」形
  (例: 移行先テーブルの `MAX(id)` より大きい行を取得する)であれば、失敗後の再実行で
  続きから進む。
- 進捗ログ: 5000件ごとにINFOログで進捗を出力する。

使用例(project-serviceへ`sites`テーブルを移す場合のイメージ):

```java
SchemaMigrationJob job = new SchemaMigrationJob(projectServiceDataSource);
MigrationResult result = job.run("legacy-sites-to-project-service", (targetConnection, batchSize) -> {
    // legacyDataSource から「まだ移行先に無い行」をbatchSize件取得してtargetConnectionへINSERTし、
    // 実際にコピーした件数を返す。0を返すとジョブは完了したとみなされる。
    ...
});
```

具体的などのテーブルをどう移すかは、各サービス抽出Issue側でこの基盤を使って実装する。

## 移行の検証手順

各サービス抽出Issueでデータ移行を行った際は、次の2点を確認する。

1. **件数突合**: 移行元テーブルの行数と、移行先テーブルの行数が一致すること。
   ```sql
   -- 移行元
   SELECT COUNT(*) FROM lets_blog.sites;
   -- 移行先
   SELECT COUNT(*) FROM lbs_project.sites;
   ```
2. **サンプル値突合**: 主キーの一部(先頭・末尾・ランダムに数件)を移行元・移行先の両方で
   取得し、主要カラムの値が一致することを確認する。
   ```sql
   SELECT * FROM lets_blog.sites WHERE id IN (1, 2, 999) ORDER BY id;
   SELECT * FROM lbs_project.sites WHERE id IN (1, 2, 999) ORDER BY id;
   ```

両方の突合が一致した後にのみ、移行元テーブルの参照をアプリケーションコードから外す
(削除は #583 legacy-api解体まで行わない)。
