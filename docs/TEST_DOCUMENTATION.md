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
// JWTを必要とするテストのフィクスチャ(上記「JWTを必要とするテストの書き方」参照)
testImplementation testFixtures(project(':libs:lbs-common'))
```

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

- [カスタムタグ生成機能仕様書](./CUSTOM_TAGS_SPECIFICATION.md)
- [開発者ガイド](./DEVELOPER_GUIDE.md)
- [E2E テスト実行ガイド](./E2E_TEST_GUIDE.md)
- [カバレッジ目標](./COVERAGE_TARGETS.md)
- [ADR-0006: サービス別のテスト戦略](./adr/0006-per-service-test-strategy.md)
