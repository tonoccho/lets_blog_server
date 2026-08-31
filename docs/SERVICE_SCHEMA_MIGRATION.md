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

**既存のMySQLデータボリュームがある環境では自動適用されない**(MySQL公式イメージの仕様。
`docker-entrypoint-initdb.d` 配下のスクリプトはデータボリュームが空の場合のみ実行される)。
新しいスキーマ自体を追加で反映するにはボリュームの再作成が必要(`docker compose down -v` 等。
既存データを消してよいことを確認してから実行すること)。

各ユーザーのパスワードは `.env` の `LBS_<SCHEMA>_DB_PASSWORD` 系変数で設定する
(`.env.example` 参照)。

### `.env` のDBパスワードを変更した場合(#667)

既にMySQLのデータボリュームが初期化済みの環境で `.env` の `LBS_<SCHEMA>_DB_PASSWORD` を
変更しても、**MySQL上の該当ユーザーのパスワードは自動的には更新されない**。コンテナを
`docker compose up -d` で再作成しても、`01-create-service-schemas.sh` は初回起動時にしか
実行されないため無反応に見える。この状態のまま気づかずにいると、アプリ側の接続文字列
(新しいパスワード)とDB側の実際のパスワード(古いまま)が食い違い、対象サービスが
`Access denied for user '<schema>'@'...' (using password: YES)` でクラッシュループする
(#654の調査時に `lbs-project` で実際に発生)。

対応方法は次の2通り。データを消さずに直したい場合はAを推奨する。

#### A. パスワードだけをMySQL側に同期する(データを消さない、推奨)

`01-create-service-schemas.sh` は `CREATE USER IF NOT EXISTS` に加えて
`ALTER USER IF EXISTS ... IDENTIFIED BY ...` を実行するため、複数回実行しても安全(冪等)。
`.env` 変更後、次の手順でMySQL上のパスワードを最新の `.env` の値に同期できる。

```bash
# 1. .envの新しい値でmysqlコンテナを再作成(環境変数を反映させるため)
docker compose up -d mysql

# 2. 初期化スクリプトを手動で再実行してMySQL上のパスワードをALTER USERで更新する
docker compose exec mysql bash /docker-entrypoint-initdb.d/01-create-service-schemas.sh

# 3. パスワード変更対象のサービスコンテナを再起動する
docker compose up -d <service>
```

#### B. ボリュームごと作り直す(既存データを破棄してよい場合のみ)

```bash
docker compose down -v   # mysql_dataボリュームを含め破棄される点に注意
docker compose up -d
```

いずれの場合も、`.env` を編集しただけでは反映されない(コンテナ再作成またはAの手順が必要)
ことを常に念頭に置くこと。

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

分割前の単一サービスが持っていたV1〜V81のマイグレーションは再配置せず、各サービスは自スキーマ用の
新規の初期マイグレーション(`V1__init_schema.sql` 等)から始めた。旧マイグレーションは
legacy-api ごと #583 で削除済み(内容は git history を参照)。

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

### identity-service の移行手順(issue #786)

identity-service は #561 の受入基準「`lbs_identity` を Flyway で管理する」が**未達のままクローズ**
されており、分割前の `lets_blog` スキーマを参照し続けていた。そのため旧スキーマの
`users` / `roles` / `role_permissions` / `user_roles` / `project_users` / `user_site_authors` は
「移行漏れの残骸」ではなく**現に参照されている生きたテーブル**である。
旧スキーマを削除・アーカイブする(#785 / #583)前に、必ず本移行を済ませること。

**この移行はサービス停止中、またはアクセスの無い時間帯に行うこと。**
移行後も legacy-api が同じ `users` を参照するため両スキーマにデータが並存する。
その間にどちらかへ書き込みが入ると乖離する(legacy-api 側の参照を断つのは #583 のスコープ)。

```bash
# 1. lbs_identity に空の6テーブルを作る(identity-service を起動すると Flyway V1 が適用される)
docker compose up -d identity
docker logs lbs-identity | grep -i flyway     # "Successfully applied 1 migration" を確認

# 2. データを移す(root 権限が必要。クロススキーマINSERTのためアプリのFlywayでは実行できない)
docker exec -i lbs-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  < scripts/migrate-identity-tables-to-lbs-identity.sql

# 3. スクリプト末尾の件数突合で、6テーブルすべて source_count = target_count を確認する
```

件数突合の出力例:

```
+-------------------+--------------+--------------+
| table_name        | source_count | target_count |
+-------------------+--------------+--------------+
| roles             |            3 |            3 |
| users             |            2 |            2 |
| role_permissions  |           30 |           30 |
| user_roles        |            2 |            2 |
| project_users     |            0 |            0 |
| user_site_authors |            2 |            2 |
+-------------------+--------------+--------------+
```

4. `docker-compose.yml` の identity-service は既に `lbs_identity` を指しているので、
   移行後に `docker compose up -d identity` で再作成する。
5. ログイン・ユーザー管理・プロジェクトメンバー管理が動作することを確認する。
6. 旧スキーマ側の6テーブルの削除は **#583(legacy-api 解体)のスコープ**。legacy-api が
   まだ `users` を参照しているため、本Issueの時点では削除しない。

## 移行の検証手順

各サービス抽出Issueでデータ移行を行った際は、次の2点を確認する。

1. **件数突合**: 移行元テーブルの行数と、移行先テーブルの行数が一致すること。
   ```sql
   -- 移行元(分割前のスキーマ。#785で廃止したため、現在は新規の移行では発生しない)
   SELECT COUNT(*) FROM <移行元スキーマ>.sites;
   -- 移行先
   SELECT COUNT(*) FROM lbs_project.sites;
   ```
2. **サンプル値突合**: 主キーの一部(先頭・末尾・ランダムに数件)を移行元・移行先の両方で
   取得し、主要カラムの値が一致することを確認する。
   ```sql
   SELECT * FROM <移行元スキーマ>.sites WHERE id IN (1, 2, 999) ORDER BY id;
   SELECT * FROM lbs_project.sites WHERE id IN (1, 2, 999) ORDER BY id;
   ```

両方の突合が一致した後にのみ、移行元テーブルの参照をアプリケーションコードから外す。

---

## 旧スキーマ `lets_blog` の廃止(issue #785、2026-09-01 実行済み)

分割前の単一スキーマ `lets_blog`(`.env` の `MYSQL_DATABASE`)は**削除した**。
以下は実行時点の記録である。

### 判断の根拠(実測)

1. **参照ゼロ**。#583 で legacy-api を削除した後、`lets_blog` に接続するサービスは無い。
   `docker-compose.yml` の全 `SPRING_DATASOURCE_URL` は `lbs_*` を指しており、
   `information_schema.processlist` の実接続も9サービスすべて `lbs_*` だった。
2. **identity 系6テーブルは移行済みで内容一致**。件数だけでなく、
   `users` は `id / email / keycloak_sub / role / enabled` の全行が `lbs_identity` と一致した
   (UNION ALL + GROUP BY HAVING COUNT(*) <> 2 が0行)。

   | テーブル | `lets_blog` | `lbs_identity` |
   |---|---:|---:|
   | `users` | 2 | 2 |
   | `roles` | 3 | 3 |
   | `role_permissions` | 30 | 30 |
   | `user_roles` | 2 | 2 |
   | `project_users` | 0 | 0 |
   | `user_site_authors` | 2 | 2 |

3. **`project_image_settings` は0行**。所有権は #583 で media-service(`lbs_media`)へ移った。
4. 残る `flyway_schema_history` は legacy-api のマイグレーション履歴で、
   legacy-api 自体が無くなったため意味を持たない。

### 実行手順(再現する場合)

```bash
# 1. 退避(root で。スキーマ専用ユーザーでは他スキーマへ触れない)
mkdir -p backups
docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" lbs-mysql \
  mysqldump --user=root --single-transaction --routines --triggers --databases lets_blog \
  > backups/lets_blog-final-before-drop-$(date +%Y%m%d-%H%M%S).sql

# 2. 削除
docker exec lbs-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE lets_blog"'

# 3. 使われなくなった grant を落とす
docker exec lbs-mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "REVOKE ALL PRIVILEGES ON \`lets\\_blog\`.* FROM '"'"'lbs_app'"'"'@'"'"'%'"'"'; FLUSH PRIVILEGES;"'
```

### あわせて変更した設定

- `docker-compose.yml`: mysql コンテナの `MYSQL_DATABASE` を削除。残すと**空ボリュームからの
  初回起動時に使われないスキーマが再作成される**。`MYSQL_USER` / `MYSQL_PASSWORD`(`lbs_app`)は
  **残した**。wordpress(`WORDPRESS_DB_USER`)と phpmyadmin(`PMA_USER`)がこのユーザーで接続し、
  マネージドWordPressのサイト別DBはプロビジョニングエージェントが root で都度作成して
  このユーザーへ個別に権限を付与するため、`MYSQL_DATABASE` が無くても機能する
- `docker-compose.yml`: `BACKUP_MYSQL_SCHEMAS` から `${MYSQL_DATABASE}` を削除(対象は `lbs_*` 9つ)
- `mysql/init/01-create-service-schemas.sh`: `lbs_backup` への `MYSQL_DATABASE` 権限付与を削除
- `scripts/db-backup.sh` / `scripts/db-restore.sh`: 単一スキーマ前提だったものを、
  `--databases` でサービス別スキーマ9つを対象にする形へ変更
- `.env.example`: `MYSQL_DATABASE` を削除

> **既存の `.env` に `MYSQL_DATABASE` が残っていても害はない**(参照元が無い)。
> `scripts/check-env.sh` が「.env にあって .env.example に無いキー」として情報表示するだけである。
