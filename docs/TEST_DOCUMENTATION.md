# テストドキュメント

## サービス構成とテストの実行方法

本プロジェクトのバックエンドはGradleマルチプロジェクト構成で、以下のモジュールに
分かれている(#587。旧単一`api/`プロジェクトからの移行は#557以降で完了済み)。

| モジュール | パス | 役割 |
|---|---|---|
| `libs:lbs-common` | `packages/lbs-common` | サービス間で共有する横断的な部品(ドメインロジックは持たない) |
| `services:gateway` | `services/gateway` | APIゲートウェイ(ルーティング・JWT検証・レート制限・相関ID) |
| `services:identity` | `services/identity` | ユーザー・ロール・権限・プロジェクトメンバー |
| `services:project` | `services/project` | プロジェクト・サイト・SSH鍵・タグデザイン |
| `services:content` | `services/content` | 投稿・カスタムタグ・レンダリング・コンテンツキャッシュ |
| `services:media` | `services/media` | 画像生成・生成画像・ダイアグラム・ComfyUI |
| `services:ai` | `services/ai` | LLM生成・記事プラン・生成ジョブ |
| `services:analytics` | `services/analytics` | GA/AdSense レポートと資格情報 |
| `services:publishing` | `services/publishing` | 公開パイプライン・一括管理・記事プレビュー |
| `services:platform` | `services/platform` | システム設定・バックアップ・ダッシュボード状態・VSCode拡張配布 |
| `services:log-writer` | `services/log-writer` | ログ書き込み |

> `services:legacy-api` は issue #583 で解体・削除した。全エンドポイントは上のいずれかへ移設済み。

### テスト用MySQLの前提

**先に読むこと。** 各サービスの `src/test/resources/application-test.yml` は接続先を
`jdbc:mysql://localhost:3306/...` に固定している(ADR-0006: Testcontainers は使わず実 MySQL を使う)。
この前提が崩れていると、テストは中身と無関係な理由で大量に落ち、本物の失敗が埋もれる(#762)。

崩れ方は2通りある。

| 症状 | 例外 | 原因 |
|---|---|---|
| ポートに何もいない | `FlywaySqlUnableToConnectToDbException` / `ConnectException` | `docker-compose.yml` の `mysql` はホストにポートを**公開していない** |
| スキーマが無い | `Unknown database 'lbs_project_test'` | `infra/mysql/init/*.sh` はデータボリュームが**空のときにしか**実行されない |

実行前に前提を確認する。

```bash
bash scripts/check-test-db.sh
```

到達性・資格情報・必要なスキーマ10件の有無を見て、足りないものと対処を出す。

#### `--force-recreate <svc>` で公開が黙って外れる(#1109)

`docker compose up -d --force-recreate media gateway` のように**素の `docker-compose.yml` だけ**で
一部のサービスを作り直すと、`depends_on` で参照される `lbs-mysql` も**オーバレイ無しの設定で**
作り直され、`127.0.0.1:3306` の公開が外れる(`docker ps` のポート欄が `127.0.0.1:3306->3306/tcp`
から `3306/tcp` に変わる)。コンテナは healthy のままなので、スタックの健全性チェックは何も言わない。
その後の `./gradlew :services:media:test` は `@SpringBootTest` 系(232件中53件など)が
`FlywaySqlUnableToConnectToDbException` で落ち、**変更と無関係なテストが赤くなる**。

- **復旧**: `docker compose -f docker-compose.yml -f docker-compose.host-tests.yml up -d mysql`
- **検知**: Gradle の `test` タスクは開始時に `127.0.0.1:3306`(`TEST_DB_HOST` / `TEST_DB_PORT` で変更可)へ
  接続を試み、到達できなければ「環境要因(3306が公開されていない)」と復旧コマンドを含む
  `WARNING` を出す。失敗にはしない(DB非依存の単体テストは動かすため)。単独では
  `bash scripts/check-test-db.sh --reachability`(別名 `--port-only`。到達可なら 0、不可なら 1)。
- **恒久策の判断(不採用)**: `docker-compose.yml` で `127.0.0.1:3306:3306` を常時公開すればオーバレイ
  自体が不要になるが、採用しない。理由: (1) ホストの 3306 を持つ他の保持者(loop-engineering の
  `lbs-test-db`、複数スタック/worktree、AT環境、リリース検証)と衝突する。(2) 全環境が使うベース
  ファイルを、ホスト側テストという一部の用途のために汚す。オーバレイは opt-in のままにし、
  外れたことを上の検知で即座に分かるようにする。

#### 実行方法は2つ

**A) コンテナの中で回す(推奨)**

```bash
bin/loop test api
```

接続先の MySQL(`lbs-test-db`)がテストスキーマ込みで自動的に用意される。何も準備しなくてよい。

> `bin/loop` は本リポジトリ外のツール(loop-engineering)で、その `api` ターゲットは
> issue #583 で削除した legacy-api を指していた可能性がある。動かない場合は下の B) を使うこと。

**B) ホストから `./gradlew` で回す**

開発スタックの MySQL を `127.0.0.1:3306` へ公開するオーバーライドを重ねる。
ループバックに限定しているのは、外部へ DB を晒さないため。

```bash
docker compose -f docker-compose.yml -f docker-compose.host-tests.yml up -d mysql
bash scripts/check-test-db.sh
./gradlew :services:content:test
```

> **注意:** `127.0.0.1:3306` を使うコンテナが複数あると衝突する
> (開発スタックの `mysql` と、コンテナ実行用の `lbs-test-db`)。どちらか一方だけを起動すること。
> 両方起動していると、テストが「起動しているつもりでない方」の MySQL に当たり、
> スキーマやデータが噛み合わずに落ちる。

スキーマだけが足りない場合は、初期化スクリプトを手動で再実行する(冪等)。

```bash
docker compose exec mysql bash /docker-entrypoint-initdb.d/02-create-test-schemas.sh
```

### media / content サービスに Chromium は要らない(#1020 / #1046)

media-service は `[recharts]` 組み込みタグのサーバーサイドレンダリングにヘッドレス Chromium を
使う(`PlaywrightConfig` → `RechartsRenderer`)。**しかしテストを回すのにブラウザは要らない。**

かつては要った。`RechartsRenderer` が eager singleton のまま素の `Browser` を注入していたため、
Chromium の実行バイナリが無いホストでは ApplicationContext の生成そのものが落ち、
`./gradlew :services:media:test` が **118件中46件**失敗していた(#1020)。落ちるのは
認可マトリクス(#772)・AdminAuthorization(#644)・Flyway 契約(#914)という、レンダリングとは
無関係なテストばかりで、認可と DB マイグレーションの回帰検知が常に赤のまま誰も読まない状態に
なっていた。上の「テスト用MySQLの前提」と同じ、**環境要因が本物の失敗を埋もれさせる**形である。

| 症状 | 例外 | 原因 |
|---|---|---|
| ブラウザが無い | `TargetClosedError` / `chrome-headless-shell: libatk-1.0.so.0` | eager な消費者が `Browser` を直接注入し、Bean 定義側の `@Lazy` を無効化していた |

#### 採った対処と、採らなかった案

**注入点に `@Lazy` を付けた**(`RechartsRenderer` のコンストラクタ引数)。Spring は `Browser` の
プロキシを注入し、実体は `browser.newPage()` が最初に呼ばれるまで作られない。実行時経路は
変わらないので、Chromium を持つコンテナでの `POST /api/render/recharts` は従来どおり動く。

- **`RechartsRenderer` 自体を `@Lazy` にする** — 採らない。`RenderController` が eager に
  注入するので実体化は結局起動時に起きる。消費者が増えるたびに全員へ付けて回ることになり、
  付け忘れがそのまま再発の形になる。
- **プロファイル分離**(テストだけ `browser` を差し替える) — 採らない。テストは通るが、
  Chromium の無いホストで**アプリを起動する**ことは相変わらずできない。`PlaywrightConfig` の
  コメントが元から述べていた意図を、テスト専用の迂回で置き換えることになる。
- **ホストへ依存パッケージ(libatk 等)を導入する** — 採らない。環境側の対処であり、
  「ブラウザの有無に関係なく実行できる」という #1020 の Goal と方向が逆。

#### 再発の検知

`services/media/src/test/java/com/letsblog/media/config/PlaywrightLazyBrowserTest.java` が
2つの角度から見張る。どちらも Chromium も DB も要らない。

1. コンテキストを起動しても `browser`/`playwright` シングルトンが**作られない**こと
   (Chromium 入りのコンテナでも意味を持つよう、例外の有無ではなく実体化の有無を見る)
2. `com.letsblog.media` の Spring 管理コンポーネントに `@Lazy` の無い `Browser`/`Playwright`
   注入点が現れたら失敗するラチェット(将来 eager な消費者が増えたときに気づくため)

> **Node 側(受け入れテスト)の Playwright は別問題。** `npm run test:at` はホストの
> `~/.cache/ms-playwright` にブラウザを要求する。こちらは実行環境を用意する話であり、
> **#1045** で扱う。#1020 の対処はこれを解決しない。

#### content-service にも同じ欠陥があった(#1046)

`com.microsoft.playwright` を使うサービスは media だけではない。content-service は
`[blogcard]`/`[amazon]` 組み込みタグのスクレイピング(`PlaywrightPageFetcher`)と、記事
プレビューのテーマ骨格取得(`PreviewSkeletonFetcher`。#1564 で削除)で同じ `PlaywrightConfig` を使っており、
**同じ形で `./gradlew :services:content:test` が 248件中53件失敗していた**(内訳は media と
同じ顔ぶれ — 認可マトリクス #772 が41件、AdminAuthorization #644 が6件、Flyway 契約 #914 が
3件、内部ブリッジが3件)。対処も同じで、**両方の注入点に `@Lazy` を付けた**。

> **media と違い、eager な消費者が2つある。**片方だけ直しても、もう片方が `browser` を
> eager に実体化するので症状はそのまま残る。「1つ直したから終わり」と読めてしまうのが
> この欠陥の質の悪いところで、下のラチェットはそのために**パッケージ全体を走査する**。

`services/content/src/test/java/com/letsblog/content/config/PlaywrightLazyBrowserTest.java` が
media 版と同じ2つの角度で見張る。走査対象は **`com.letsblog.content`**。

> **移植時の罠。** 走査対象のパッケージ名を移植元(`com.letsblog.media`)のまま残すと、候補が
> 0件になってラチェットは**永遠に緑のまま何も検知しない**(#994 と同型)。content 版には
> 「既知の消費者2つが走査候補に入っていること」を確かめる3本目のテストを足してあり、
> 向け先を取り違えたらそれが落ちる。

---

各サービスのテストは、プロジェクトルートから`./gradlew`でサービスごとに独立して実行できる
(`.claude/CLAUDE.md`セクション19「Running Locally」と同じコマンド)。

```bash
# 全サービス
./gradlew lint test

# 1サービスだけ(例: content)
./gradlew :services:content:lint :services:content:test

# 1サービスの特定テストクラスだけ
./gradlew :services:content:test --tests "CustomTagGenerationIntegrationTest"

# lbs-common(共有ライブラリ)
./gradlew :libs:lbs-common:lint :libs:lbs-common:test
```

**CI は無い。** 上記のコマンドをローカルで実行して検証すること
(`README.md` の「品質の担保」参照)。かつては変更のあったサービスだけをマトリクスで
`lint`+`test`+`jacocoTestReport`し Codecov へアップロードする定義があったが、
GitHub Actions は元から無効化されており、GitLab 移行時に定義ごと削除した(#1027)。

## 性能テストの現状(issue #915)

**負荷試験のツール(JMH / k6)は持たない。** 性能に関する自動検証は
`apps/web/e2e/features/custom-tag/performance.feature`(受け入れテスト)だけである。
#915 の時点では `apps/web/e2e/performance.spec.ts` に置いていたが、#938 (AT-12) で
`.feature` へ移行し、spec は削除した。

| 対象 | 目標値 |
|---|---|
| API レスポンス(`/api/custom-tags/validate`、1件) | < 2秒 |
| 複数リクエストの並列処理(5件同時) | < 3秒 |
| タグ画面のページロード | < 3秒 |

**LLM 応答時間(< 10秒)の検証は廃止した。** 閾値は当時の Ollama の実測に由来し、
受け入れテストが LLM をスタブへ向ける現在の構成では**スタブの往復時間**を測ることにしか
ならないため(#938。理由は同 feature の冒頭に書いてある)。

### JMH / k6 をやめた理由

#24 で JMH ベンチマークと k6 負荷試験、専用の CI ワークフローが入ったが、
**一度も実態へ追随されないまま #583(legacy-api の解体)で削除された**。
削除時点で既に動作していなかったことが判明している。

- **JMH は実行するベンチマークが0件だった。** `me.champeau.jmh` プラグインと依存だけがあり、
  `find services/legacy-api/src -path '*jmh*' -o -name '*Benchmark*'` は0件。
  ワークフローは毎回「No benchmark results found」を出していた
- **k6 は存在しないエンドポイントを叩いていた。** `/api/articles`・`/api/articles/preview` は
  現在どのサービスにも無い(投稿は `/api/posts`、プレビューは `/api/projects/{id}/preview/**`)
- **認証が考慮されていない。** #772 以降 `/api/**` は全サービスで Keycloak JWT 必須だが、
  スクリプトはトークンを付けないため、パスが正しくても401になる
- **実行環境の前提が古い。** 単一 jar 起動を想定しており、現在の11コンテナ構成では成立しない

「あるのに動かない CI」は無いより悪い(緑だから大丈夫という誤解を生む)ため、
**復活させずに廃止する**と判断した(#915)。

### 再び必要になったら

負荷試験を入れ直す場合は、以下を満たす形で新規に作ること。旧スクリプトは流用しない。

- `docker compose up -d` 済みのスタックに対し、**gateway 経由**で叩く
  (`scripts/wait-for-stack-healthy.sh` が使える)
- Keycloak からトークンを取得する(`scripts/provision-e2e-keycloak-users.sh` と
  `docs/e2e-testing.md` に先例がある)
- 閾値は現構成で**実測してから**設定する。旧スクリプトの p95/p99 は単一 jar 時代の値で根拠が無い

## JWTを必要とするテストの書き方

JWT認証を伴うエンドポイント・ロジックのテストは、`packages/lbs-common`が
`java-test-fixtures`として提供する`com.letsblog.common.testfixtures.JwtTestFixtures`を使う
(#587)。利用側のサービスは`build.gradle`に以下を追加する(全サービス追加済み)。

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

**場所**: `services/content/src/test/java/com/letsblog/content/service/CustomTagGenerationServiceTest.java`

> issue #576 でカスタムタグの所有権が content-service へ移り、#583 の legacy-api 削除時に
> Spring Boot コンテキストを起動する統合テストから、サービス層の単体テストへ整理された。
> 下表のうち「DB保存」を伴うシナリオは、現在は `CustomTagServiceTest` と
> content-service の `AuthorizationCoverageTest` が分担している。

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
./gradlew :services:content:test --tests "CustomTagGeneration*"
```

### 2. E2E テスト（Playwright）

**場所**: `apps/web/e2e/`

**説明**: ユーザーが実際にUIを操作する場合の動作を検証します。ブラウザ上での実際の操作フローを自動テストします。

#### 2.1 カスタムタグ（`e2e/features/custom-tag/`）

#938 (AT-12) で受け入れテスト（Gherkin）へ移行した。6ファイル・20シナリオで、
生成と検証・編集と削除・プレビューとCSS配信・テンプレート共有・コンテンツキャッシュ・
応答時間を扱う。実行と記法は
[ACCEPTANCE_TESTING.md](ACCEPTANCE_TESTING.md) を参照。

`custom-tag-generation.spec.ts` に残っていた「レスポンシブテスト: モバイルビューポートでも
生成フォームを操作できる」1件は、AT-18 (#944) が横断的なレスポンシブ検証
（`e2e/features/ui-quality/responsive.feature`）へ引き取り、spec は削除した。

**実行方法**:

```bash
cd apps/web

# 依存関係インストール
npm install

# E2Eテスト実行
npm run test:e2e

# UIモード（ブラウザ表示）で実行
npm run test:e2e:ui

# デバッグモード
npm run test:e2e:debug
```

#### 2.2 パフォーマンス（`e2e/features/custom-tag/performance.feature`）

| # | シナリオ | 目標値 | 説明 |
|---|---------|--------|------|
| 1 | 検証APIが所定の時間内に応答する（1件） | < 2秒 | `/api/custom-tags/validate` |
| 2 | 検証APIが所定の時間内に応答する（5件同時） | < 3秒 | 同時要求の完了まで |
| 3 | タグ画面のページロードが3秒以内に完了する | < 3秒 | 2回目の遷移（1回目はウォームアップ） |

**実行方法**:

```bash
cd apps/web
npm run test:at -- --grep "@custom-tag"
```

#### 2.3 セキュリティテスト（security.spec.ts）

| # | テスト名 | 検証項目 |
|---|---------|---------|
| 1 | CSRF保護 | CSRFトークンの確認 |
| 2 | SQLインジェクション | 特殊文字の安全処理 |
| 3 | 入力サニタイズ | ユーザー入力のサニタイズ確認 |

XSS検出（script タグ・イベントハンドラ・`javascript:`・CSS インジェクション）と
テンプレート削除の認可は #938 (AT-12) で `e2e/features/custom-tag/` へ移行した。

**実行方法**:

```bash
cd apps/web
npm run test:e2e -- security.spec.ts
```

## 環境セットアップ

### API テスト環境

**必要な設定**:
- Java 21
- Gradle(ルートのマルチプロジェクトビルド。`packages/lbs-common` + `services/*`)
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
`infra/mysql/init/02-create-test-schemas.sh` が作る。

| サービス | テストスキーマ | 作られ方 |
|---|---|---|
| content / media / ai / analytics / platform | `lbs_{content,media,ai,analytics,platform}_test` | `infra/mysql/init/02-create-test-schemas.sh`(#762で追加。以前は `scripts/setup-test-db.sh` だけが作っており、開発スタックのMySQLには作られていなかった。同スクリプトは参照されなくなったため #846 で削除済み) |
| identity / project / publishing / log-writer | `lbs_{identity,project,publishing,log}_test` | 同上(#772で追加) |

> `lets_blog_test`(legacy-api 用)は #583 のサービス削除と #785 の旧スキーマ廃止に伴い不要になった。

**注意:** `infra/mysql/init/*.sh` は MySQL 公式イメージの仕様により**データボリュームが空のときにしか
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

テーブルは各サービスの Flyway migration が起動時に作る。**9サービスすべてが
`spring.flyway.enabled: true` + `spring.jpa.hibernate.ddl-auto: validate`** で、
マイグレーションとエンティティ定義の食い違いは context load の失敗として現れる。
identity だけ `flyway.enabled: false` + `ddl-auto: create-drop` になっており V1 が
テストで一度も実行されていなかったが、issue #914 で他8サービスへ揃えた。

冪等性・履歴・チェックサムの検証は各サービスの `MigrationContractTest`
(共通実装は `packages/lbs-common` の `MigrationContract`、issue #914)が行う。

### Web テスト環境

**必要な設定**:
- Node.js
- npm
- Playwright

**インストール**:

```bash
cd apps/web
npm install

# Playwrightブラウザのインストール(root 不要)
npm run playwright:install

# ブラウザが依存する OS 共有ライブラリ(root が要る。内容を確認して自分で実行する)
sudo npx playwright install-deps
```

2段目を飛ばすと、バイナリは在るのに `error while loading shared libraries` で
起動しない状態になる。手順と導入方針の理由は
[e2e-testing.md §3.3](e2e-testing.md) を参照(issue #1045)。

#### web の必須検証コマンド

`web` を変更したら、以下の3つを**すべて**実行すること(issue #720)。

```bash
cd apps/web
npm run lint        # ESLint
npx tsc --noEmit    # 型チェック
npx jest            # ユニットテスト
```

**`npx tsc --noEmit` を省略しないこと。** Jest は ts-jest / babel でトランスパイルするだけで
型検査をしないため、**Jest が全件成功していても型エラーは残りうる**。実際 #720 では
`npx jest` が183件すべて成功する一方で `npx tsc --noEmit` が6件のエラーを報告していた
(e2e スペックの型不一致3件と、`SiteListTable.test.tsx` のモックが `Site` / `Project` の
フィールド追加に追随していないもの3件)。

`apps/web/tsconfig.json` の `include` は `**/*.ts` / `**/*.tsx` なので、`e2e/` と
`__tests__/` も型チェックの対象である。`npm run build`(= `next build`)も型チェックを
行うため、ビルドを通す前にここで検出できる。

なお **CI は無い**ため、これらは**自動では強制されない**。ローカルで実行して確認すること。

## テスト実行パイプライン

**このリポジトリに CI は無い**(2026-09-03 の GitLab CE 移行時に決定。#1027)。
テストは手元で実行する。プッシュ前に、上記「サービス構成とテストの実行方法」に
記載のコマンドを回して検証すること。

代わりに品質を担保している仕組み(git フック、Claude Code フック、カバレッジゲート)は
`README.md` の「品質の担保」を参照。

### 移行前にあったもの

バックエンドの CI 定義は、変更のあったサービスだけを `lbs-common` と全9サービス+`gateway`
のマトリクスで検出し、それぞれ独立して `lint`+`test`+`jacocoTestReport` を実行、Codecov へ
サービス別 `flags:` でカバレッジをアップロードするものだった(#557、#587)。フロントエンドにも
同様の定義があった。

GitHub Actions は元から意図的に無効化されており、実際には一度も動いていない。定義は
移行時に削除した。将来 CI を持つ判断に変わったときのために、その設計に埋まっていた教訓は
ADR([0010](adr/0010-github-to-gitlab-migration.md))に残してある。

## テスト結果レポート

### ユニットテスト

テスト結果は自動的に生成されます：

```bash
# テスト結果レポート(例: content)
cat services/content/build/reports/tests/test/index.html
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

**原因**: ブラウザ本体、または**ブラウザが依存する OS 共有ライブラリ**が入っていない。
症状が似ているのに対処が違うので、どちらかを見分ける必要がある(issue #1045)。

**解決方法**: `apps/web/e2e/global-setup.ts` の前提確認が、どちらが足りないかを判別して
その段の導入コマンドを示す。示されたほうを実行する。

```bash
cd apps/web

# 1. ブラウザ本体が無い(`Executable doesn't exist`)場合。root 不要
npm run playwright:install

# 2. 共有ライブラリが無い(`error while loading shared libraries`)場合。root が要る
sudo npx playwright install-deps
```

手順の全体と導入方針の理由は [e2e-testing.md §3.3](e2e-testing.md) を参照。

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

## Gradle のメモリ予算(#1633)

無人パイプラインのユニットは `MemoryHigh=4G` / `MemoryMax=6G`。Gradle(デーモン + テスト JVM)は 4G の枠に収める。
上限は `gradle.properties`(デーモン・並列度)とルート `build.gradle` の `subprojects`(Test タスク)で決まる。
`org.gradle.jvmargs` を書くと既定値は引き継がれないため、必要な値はすべて明示している。

### 実測

測定コマンド(全 11 モジュール。`cleanTest` を足すのは、足さないと Gradle が前回結果を再利用して実行されないため):

```bash
./gradlew --stop
systemd-run --user --wait --collect --pipe -p MemoryMax=4G -p MemorySwapMax=0 \
  --working-directory="$PWD" --setenv=PATH="$PATH" \
  ./gradlew --no-daemon cleanTest test jacocoTestReport
```

| | Memory peak | 所要時間 | tests / failures / errors / skipped | oom-kill |
|---|---|---|---|---|
| 変更前(2026-10-05, 既定値) | 1.7G | 7m32s | 4588 / 0 / 0 / 2 | なし |
| 変更後 | 1.6G | 7m56s | 4588 / 0 / 0 / 2 | なし |

件数は `**/build/test-results/test/*.xml` の合計。`OutOfMemoryError` はログに出ていない。
ピークが 1.6G と 4G の約 4 割なのは、`org.gradle.parallel=false` と `maxParallelForks=1` で同時に動くテスト JVM が 1 つだけだから。
変更前の ps で見た 2026-10-05 の OOM(java の anon-rss 約 1.39GiB)は、ホスト全体のメモリ逼迫(lbs-* コンテナと GitLab で約 25GiB、swap 枯渇)の中で起きた。Gradle 単体の使用量はこの枠に十分収まる。

### 上限値と根拠

| 設定 | 値 | 根拠 |
|---|---|---|
| デーモン `-Xms/-Xmx` | 256m / 512m | Gradle 9.7.0 の既定と同じ。ビルドスクリプトの評価だけで足りる |
| デーモン `MaxMetaspaceSize` | 384m | 既定と同じ |
| `ReservedCodeCacheSize` / `MaxDirectMemorySize` | 128m / 128m | 既定では上限が実質無い領域。ヒープ外の常駐を有界にする |
| テスト JVM `maxHeapSize` | 512m | 既定と同じ。`@SpringBootTest` 61 件のコンテキストキャッシュで足りている |
| テスト JVM `MaxMetaspaceSize` | 256m | 既定は無制限だった。実測の失敗なしで収まる値 |
| `org.gradle.parallel` / `maxParallelForks` | false / 1 | 既定どおり。意図を残すために明示 |
| `org.gradle.workers.max` | 4 | 既定は CPU コア数(8)。意図して絞った上限。`parallel=false` では同時に動くテスト JVM は 1 つなので、1.6G の実測には効いておらず、将来 parallel を有効にしたときの歯止め |
| `org.gradle.daemon.idletimeout` | 15 分 | 既定は 3 時間。pipeline のデーモンが次の Issue の間も枠を消費し続けるのを避ける |

### 6G 枠の内訳(目安)

| 項目 | 最大 |
|---|---|
| Gradle デーモン(ヒープ 512m + Metaspace 384m + コードキャッシュ 128m + ダイレクト 128m + スタック等) | 約 1.4G |
| テスト JVM 1 つ(同 512m + 256m + 128m + 128m + JaCoCo エージェント等) | 約 1.2G |
| claude 本体 | 約 0.4G |
| jest・Playwright など他ツール | 残り(約 3G) |

### 確認方法

`./gradlew --stop` の後に `./gradlew :services:identity:test` を実行し、`ps -eo args` でデーモンの `-Xmx512m -XX:MaxMetaspaceSize=384m` と
`Gradle Test Executor` の `-Xmx512m -XX:MaxMetaspaceSize=256m` を確認できる。
Dockerfile は `gradle.properties` を COPY せず、イメージ内では `bootJar` と `copyRuntimeLibs`(`--no-daemon`)しか動かないため影響しない。

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
