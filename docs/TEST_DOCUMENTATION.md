# テストドキュメント

## サービス構成とテストの実行方法

本プロジェクトのバックエンドはGradleマルチプロジェクト構成で、以下のモジュールに
分かれている(#587。旧単一`api/`プロジェクトからの移行は#557以降で完了済み)。

| モジュール | パス | 役割 |
|---|---|---|
| `libs:lbs-common` | `libs/lbs-common` | サービス間で共有する横断的な部品(ドメインロジックは持たない) |
| `services:legacy-api` | `services/legacy-api` | カットオーバー前の中心的なAPIサービス(Phase 19で段階的に分割予定) |
| `services:identity` | `services/identity` | ユーザー・ロール・権限管理 |
| `services:log-writer` | `services/log-writer` | ログ書き込み |
| `services:gateway` | `services/gateway` | APIゲートウェイ |

### テスト用MySQLの前提

**先に読むこと。** 各サービスの `src/test/resources/application-test.yml` は接続先を
`jdbc:mysql://localhost:3306/...` に固定している(ADR-0006: Testcontainers は使わず実 MySQL を使う)。
この前提が崩れていると、テストは中身と無関係な理由で大量に落ち、本物の失敗が埋もれる(#762)。

崩れ方は2通りある。

| 症状 | 例外 | 原因 |
|---|---|---|
| ポートに何もいない | `FlywaySqlUnableToConnectToDbException` / `ConnectException` | `docker-compose.yml` の `mysql` はホストにポートを**公開していない** |
| スキーマが無い | `Unknown database 'lbs_project_test'` | `mysql/init/*.sh` はデータボリュームが**空のときにしか**実行されない |

実行前に前提を確認する。

```bash
bash scripts/check-test-db.sh
```

到達性・資格情報・必要なスキーマ10件の有無を見て、足りないものと対処を出す。

#### 実行方法は2つ

**A) コンテナの中で回す(推奨)**

```bash
bin/loop test api
```

接続先の MySQL(`lbs-test-db`)がテストスキーマ込みで自動的に用意される。何も準備しなくてよい。

**B) ホストから `./gradlew` で回す**

開発スタックの MySQL を `127.0.0.1:3306` へ公開するオーバーライドを重ねる。
ループバックに限定しているのは、外部へ DB を晒さないため。

```bash
docker compose -f docker-compose.yml -f docker-compose.host-tests.yml up -d mysql
bash scripts/check-test-db.sh
./gradlew :services:legacy-api:test
```

> **注意:** `127.0.0.1:3306` を使うコンテナが複数あると衝突する
> (開発スタックの `mysql` と、コンテナ実行用の `lbs-test-db`)。どちらか一方だけを起動すること。
> 両方起動していると、テストが「起動しているつもりでない方」の MySQL に当たり、
> スキーマやデータが噛み合わずに落ちる。

スキーマだけが足りない場合は、初期化スクリプトを手動で再実行する(冪等)。

```bash
docker compose exec mysql bash /docker-entrypoint-initdb.d/02-create-test-schemas.sh
```

---

各サービスのテストは、プロジェクトルートから`./gradlew`でサービスごとに独立して実行できる
(`.claude/CLAUDE.md`セクション19「Running Locally」と同じコマンド)。

```bash
# 全サービス
./gradlew lint test

# 1サービスだけ(例: legacy-api)
./gradlew :services:legacy-api:lint :services:legacy-api:test

# 1サービスの特定テストクラスだけ
./gradlew :services:legacy-api:test --tests "CustomTagGenerationIntegrationTest"

# lbs-common(共有ライブラリ)
./gradlew :libs:lbs-common:lint :libs:lbs-common:test
```

CIでは`.github/workflows/api-services-test.yml`が、変更のあったサービスだけをマトリクスで
`lint`+`test`+`jacocoTestReport`し、Codecovへサービス別`flags:`でカバレッジをアップロードする
(GitHub Actions自体は本リポジトリで意図的に無効化されているため、CI上では実行されない。
`README.md`/`.claude/CLAUDE.md`参照)。

## JWTを必要とするテストの書き方

JWT認証を伴うエンドポイント・ロジックのテストは、`libs/lbs-common`が
`java-test-fixtures`として提供する`com.letsblog.common.testfixtures.JwtTestFixtures`を使う
(#587)。利用側のサービスは`build.gradle`に以下を追加する(`services/legacy-api`・
`services/identity`は追加済み)。

```gradle
testImplementation testFixtures(project(':libs:lbs-common'))
```

使用例:

```java
// SecurityContextHolderへ手動でJwtAuthenticationTokenを設定する単体テスト
SecurityContextHolder.getContext().setAuthentication(
        new JwtAuthenticationToken(JwtTestFixtures.jwt("keycloak-sub-1", "admin")));

// @SpringBootTest + MockMvcの統合テスト
mockMvc.perform(get("/api/projects")
        .with(JwtTestFixtures.jwtRequestPostProcessor("keycloak-sub-1", "admin")))
    .andExpect(status().isOk());
```

DBへ接続するテスト・サービス間契約テスト・他サービス呼び出しのモック方針については、
[ADR-0006: サービス別のテスト戦略](adr/0006-per-service-test-strategy.md)を参照。

---

# カスタムタグ生成機能テストドキュメント

## 概要

このドキュメントは、Issue #54 で実装された「カスタムタグ生成機能の統合テストとE2Eテスト」について、テストの種類、実行方法、検証項目を説明します。

## テストの種類

### 1. 統合テスト（Integration Tests）

**場所**: `services/legacy-api/src/test/java/com/letsblog/api/integration/CustomTagGenerationIntegrationTest.java`

**説明**: Spring Boot の実際のアプリケーションコンテキストを使用して、複数のコンポーネント（Controller、Service、Repository）が正しく連携することを検証します。

**テストシナリオ**:

| # | テスト名 | 説明 | 検証項目 |
|---|---------|------|---------|
| 1 | 正常系フロー | プロンプト入力からタグ生成・保存までの完全フロー | 生成成功、DB保存、レスポンス構造 |
| 2 | HTMLエラー | OllamaレスポンスにHTMLがない場合 | エラーハンドリング |
| 3 | 重複タグ名 | 既に存在するタグ名を指定した場合 | 重複チェック、Conflict レスポンス |
| 4 | XSS対策（Script） | scriptタグを含むHTMLを検出 | セキュリティバリデーション |
| 5 | XSS対策（Event） | イベントハンドラを検出 | セキュリティバリデーション |
| 6 | 複数プロジェクト | グローバル・プロジェクト固有タグの独立性 | タグの独立管理 |
| 7 | バリデーション | HTMLとCSSのバリデーション | 検証エンドポイント動作 |
| 8 | 不正HTML | イベントハンドラを含むHTML検出 | セキュリティバリデーション |

**実行方法**:

```bash
# プロジェクトルートから
./gradlew :services:legacy-api:test --tests "CustomTagGenerationIntegrationTest"
```

### 2. E2E テスト（Playwright）

**場所**: `web/e2e/`

**説明**: ユーザーが実際にUIを操作する場合の動作を検証します。ブラウザ上での実際の操作フローを自動テストします。

#### 2.1 カスタムタグ生成フロー（custom-tag-generation.spec.ts）

| # | テスト名 | 説明 |
|---|---------|------|
| 1 | 完全フロー | プロンプト入力→生成→プレビュー→保存 |
| 2 | エラー処理 | 不正なタグ名入力時のエラー表示 |
| 3 | XSS検証 | XSS脆弱性を含むHTMLの検出と警告 |
| 4 | レスポンシブ | モバイルデバイスでの操作確認 |
| 5 | テンプレート操作 | テンプレート検索→クローン→カスタマイズ→保存 |
| 6 | エラーハンドリング | Ollama接続失敗時のエラー表示 |

**実行方法**:

```bash
cd web

# 依存関係インストール
npm install

# E2Eテスト実行
npm run test:e2e

# UIモード（ブラウザ表示）で実行
npm run test:e2e:ui

# デバッグモード
npm run test:e2e:debug
```

#### 2.2 パフォーマンステスト（performance.spec.ts）

| # | テスト名 | 目標値 | 説明 |
|---|---------|--------|------|
| 1 | Ollama応答時間 | < 10秒 | プロンプト入力から生成完了までの時間 |
| 2 | APIレスポンス | < 2秒 | /api/custom-tags/validate エンドポイント |
| 3 | UIロード時間 | < 3秒 | ページロード時間 |
| 4 | 並列リクエスト | < 3秒 | 5つのリクエストの並列処理時間 |

**実行方法**:

```bash
cd web
npm run test:e2e -- performance.spec.ts
```

#### 2.3 セキュリティテスト（security.spec.ts）

| # | テスト名 | 検証項目 |
|---|---------|---------|
| 1 | XSS - Script | scriptタグの検出 |
| 2 | XSS - Event | イベントハンドラの検出 |
| 3 | XSS - Protocol | JavaScriptプロトコルの検出 |
| 4 | CSS Injection | behavior プロパティの検出 |
| 5 | CSRF保護 | CSRFトークンの確認 |
| 6 | 認可テスト | ユーザー間のアクセス制御 |
| 7 | SQLインジェクション | 特殊文字の安全処理 |
| 8 | 入力サニタイズ | ユーザー入力のサニタイズ確認 |

**実行方法**:

```bash
cd web
npm run test:e2e -- security.spec.ts
```

## 環境セットアップ

### API テスト環境

**必要な設定**:
- Java 21
- Gradle(ルートのマルチプロジェクトビルド。`libs/lbs-common` + `services/*`)
- Spring Boot 4.1.0
- Spring Security Test

**依存関係追加**（各サービスの`build.gradle`）:

```gradle
testImplementation 'org.springframework.boot:spring-boot-starter-test'
testImplementation 'org.springframework.security:spring-security-test'
// @AutoConfigureMockMvcを使う統合テスト向け
testImplementation 'org.springframework.boot:spring-boot-webmvc-test'
// JWTを必要とするテストのフィクスチャ(上記「JWTを必要とするテストの書き方」参照)
testImplementation testFixtures(project(':libs:lbs-common'))
```

#### サービス別のテスト用スキーマ

ADR-0006 のとおり Testcontainers は使わず、実 MySQL の**サービス専用テストスキーマ**へ接続する
(各サービスの `src/test/resources/application-test.yml`)。スキーマは
`mysql/init/02-create-test-schemas.sh` が作る。

| サービス | テストスキーマ | 作られ方 |
|---|---|---|
| legacy-api | `lets_blog_test` | `mysql/init/02-create-test-schemas.sh`(#762で追加。以前は `scripts/setup-test-db.sh` だけが作っており、開発スタックのMySQLには作られていなかった) |
| content / media / ai / analytics / platform | `lbs_{content,media,ai,analytics,platform}_test` | `mysql/init/02-create-test-schemas.sh` |
| identity / project / publishing / log-writer | `lbs_{identity,project,publishing,log}_test` | 同上(#772で追加) |

**注意:** `mysql/init/*.sh` は MySQL 公式イメージの仕様により**データボリュームが空のときにしか
実行されない**。既に MySQL を動かしている環境であとからスキーマが増えると、
`Unknown database 'lbs_project_test'` のようなエラーでテストが落ちる。ボリュームを作り直さずに
追随するには、テストの接続先 MySQL(各 `application-test.yml` の `localhost:3306`)に対して
不足しているスキーマを手で作る。

スキーマを足すだけなら、初期化スクリプトの手動再実行で足りる(冪等。上の「テスト用MySQLの前提」参照)。

```bash
docker compose exec mysql bash /docker-entrypoint-initdb.d/02-create-test-schemas.sh
```

個別に作る場合は以下。`<container>` はテストの**接続先**MySQLのコンテナ名で、
コンテナ実行(`bin/loop test api`)なら `lbs-test-db`、ホスト実行なら開発スタックの `lbs-mysql`。
`lbs-mysql` はホストにポートを公開していないため、ホストから直接は繋がらない
(`docker-compose.host-tests.yml` を重ねる必要がある)。

```bash
docker exec -i <container> sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD"' <<'SQL'
CREATE DATABASE IF NOT EXISTS lbs_identity_test   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS lbs_project_test    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS lbs_publishing_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS lbs_log_test        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON `lbs_identity_test`.*   TO 'test_user'@'%';
GRANT ALL PRIVILEGES ON `lbs_project_test`.*    TO 'test_user'@'%';
GRANT ALL PRIVILEGES ON `lbs_publishing_test`.* TO 'test_user'@'%';
GRANT ALL PRIVILEGES ON `lbs_log_test`.*        TO 'test_user'@'%';
FLUSH PRIVILEGES;
SQL
```

テーブルは各サービスの Flyway migration が起動時に作る。identity のみ Flyway を持たない
(移行管理は legacy-api 側)ため、テストでは `ddl-auto: create-drop` でエンティティ定義から作る。

### Web テスト環境

**必要な設定**:
- Node.js
- npm
- Playwright

**インストール**:

```bash
cd web
npm install

# Playwrightブラウザのインストール
npx playwright install
```

## テスト実行パイプライン（CI/CD）

### GitHub Actions

バックエンドのCI定義は`.github/workflows/api-services-test.yml`(#557、#587で更新)。
変更のあったサービスだけを`lbs-common`/`legacy-api`/`log-writer`/`gateway`/`identity`の
マトリクスで検出し、それぞれ独立して`lint`+`test`+`jacocoTestReport`を実行、Codecovへ
サービス別`flags:`でカバレッジをアップロードする。フロントエンド(`web`)のCI定義は
`.github/workflows/frontend-test.yml`を参照。

GitHub Actions自体は本リポジトリ全体で意図的に無効化されている(`README.md`参照)ため、
これらのワークフローファイルはPR上では実行されない。プッシュ前に、上記「サービス構成と
テストの実行方法」に記載のコマンドをローカルで実行して検証すること。

## テスト結果レポート

### ユニットテスト

テスト結果は自動的に生成されます：

```bash
# テスト結果レポート(例: legacy-api)
cat services/legacy-api/build/reports/tests/test/index.html
```

### E2E テスト

```bash
# HTMLレポート
npx playwright show-report
```

## トラブルシューティング

### Ollama 接続エラー

**原因**: Ollama サーバーが起動していない

**解決方法**:

```bash
# Ollama を起動
ollama serve
```

### Playwright ブラウザエラー

**原因**: ブラウザがインストールされていない

**解決方法**:

```bash
npx playwright install
```

### ポート競合エラー

**原因**: 別のプロセスが port 3000 を使用している

**解決方法**:

```bash
# ポート確認
lsof -i :3000

# プロセス削除
kill -9 <PID>
```

## テストカバレッジ

### API テストカバレッジ

- CustomTagGenerationService: 90%+
- CustomTagValidationService: 95%+
- CustomTagController: 85%+

### Web テストカバレッジ

- E2E フロー: 80%+ （主要パス）
- セキュリティ検証: 100%
- パフォーマンス測定: 重要パス対象

## 今後の改善

1. **自動スクリーンショット**: CI/CD でテスト失敗時のスクリーンショットを自動収集
2. **負荷テスト**: k6 や JMeter による負荷テスト
3. **可視化テスト**: Applitools による UI 変更検出
4. **Lighthouse 統合**: パフォーマンス スコア自動化
5. **カバレッジ向上**: API テスト カバレッジ 95%+ を目指す

## 関連ドキュメント

- [E2E テストの実行と設計方針](./e2e-testing.md)
- [E2E 検証ガイド](./e2e-validation-guide.md)
- [カバレッジ目標](./COVERAGE_TARGETS.md)
- [ADR-0006: サービス別のテスト戦略](./adr/0006-per-service-test-strategy.md)
