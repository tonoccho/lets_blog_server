# 受け入れテストガイド(Gherkin / playwright-bdd)

受け入れテストの**記述形式・配置規約・タグ規約・実行方法**、および既存 E2E spec からの
移行方針をまとめる。基盤は issue #926(AT-0)で導入した。

対象読者は、AT-1〜AT-19(#927〜#945)で各ドメインの受け入れテストを書く実装者。
インフラ寄りの前提(スタック起動・Keycloak プロビジョニング・テストデータ・トラブルシューティング)
は [e2e-testing.md](e2e-testing.md) と重複させず、そちらを参照する。

---

## 1. 何を受け入れテストと呼ぶか

受け入れテストは、**利用者から観測できるふるまい**を、利用者の言葉で書いたものである。

- 受け入れ基準は `.feature` に日本語の Gherkin で書く。仕様書とテストを二重に持たない。
- 実装の内部構造(クラス名・テーブル名・内部APIの形)はシナリオに書かない。
  それらは単体テスト・契約テストの担当。
- 1シナリオ = 1つの受け入れ基準。[ACCEPTANCE_CRITERIA.md](ACCEPTANCE_CRITERIA.md) の
  トレーサビリティ表から、対応するシナリオを引けるようにする。`.feature` を実装したら、
  同じPRでカタログの「対応シナリオ」列と「状態」を更新する。

---

## 2. なぜ playwright-bdd か

`@cucumber/cucumber` 単体は独自のランナーを持ち込む。その場合、既存の
[apps/web/playwright.config.ts](../apps/web/playwright.config.ts) が持つ次の資産をすべて再実装することになる。

- `globalSetup` — 全サービスの healthy 待ちと公開URL/Keycloak への疎通確認
- `use.ignoreHTTPSErrors` — reverse-proxy の自己署名証明書
- trace / video / screenshot の失敗時取得
- ブラウザ別プロジェクト分割、`retries`、ワーカー制御

`playwright-bdd` は `.feature` を **Playwright のテストへ変換する**だけなので、
ランナーは Playwright のまま。上記をそのまま使える。

```
.feature ──(bddgen)──> apps/web/.features-gen/**/*.spec.js ──(playwright test)──> 実行
```

生成物 `apps/web/.features-gen/` はコミットしない。`.gitignore` / `eslint.config.mjs` /
`tsconfig.json` の3か所で除外している(#848 と同型の再発防止。**除外を足すときは3か所とも**)。

---

## 3. 配置規約

| パス | 内容 |
| --- | --- |
| `apps/web/e2e/features/<domain>/*.feature` | 受け入れ基準。日本語の Gherkin で書く |
| `apps/web/e2e/steps/fixtures.ts` | 全ステップ定義が共有する `test` インスタンスと `Given/When/Then` |
| `apps/web/e2e/steps/<domain>.steps.ts` | ドメイン固有のステップ定義 |
| `apps/web/e2e/steps/common.steps.ts` | ドメイン横断の共通ステップ(ログイン済み状態など) |
| `apps/web/e2e/support/index.ts` | 既存 `apps/web/e2e/helpers.ts` の再エクスポート |
| `apps/web/e2e/*.spec.ts` | 移行前の既存 Playwright spec(§7) |

`<domain>` は AT Issue の区切りに合わせる:
`auth` / `users` / `projects` / `posts` / `bulk` / `ai` / `plans` / `media` / `diagrams` /
`content` / `analytics` / `system` / `logs` / `extension` / `cross-cutting`。

### support/ を「移設」しないこと

`helpers.ts` は移行前の13 spec が依存している。移行が終わるまでは spec とステップ定義の
両方から使われるため、`support/index.ts` は helpers を**再エクスポートするだけ**にしてある。
ステップ定義は `../support` を、既存 spec は `./helpers` を参照する。
全 spec の移行が終わった時点で helpers の実体を `support/` へ移せばよい。

### フィクスチャを足すとき

`apps/web/e2e/steps/fixtures.ts` の `test` を拡張する。ステップ定義ファイルごとに
`createBdd()` を呼び直すと、フィクスチャの型が食い違って生成時に落ちる。

---

## 4. タグ規約

タグはシナリオ(または `機能:`)に付ける。Playwright のテストタグへ変換されるため、
`--grep` / `--grep-invert` でそのまま絞り込める。

| タグ | 意味 | 付ける基準 |
| --- | --- | --- |
| `@slow` | 分単位で時間がかかる | ComfyUI の実生成、WordPress への実公開、サイトのプロビジョニング |
| `@destructive` | 環境の共有状態を壊す | 一括削除、バックアップ/リストア、ユーザーの無効化、ログアウト。**前後で復旧すること**。§10 の `at-destructive` 段階で最後にまとめて実行される |
| `@stub` | 外部依存スタブの起動が前提 | LLM / GA / AdSense / Brave Search / OpenAI画像生成 / GitHub(§9) |
| `@api` | UI を経由せず HTTP で検証する | 認証ゲート、拡張のAPI、非同期経路 |

ドメインタグ(`@auth`, `@media` など)は自由に付けてよい。上記4つは**意味が固定**なので、
別の意味で使わないこと。

`@stub` が付いたシナリオは、スタブが起動していなければ**スキップではなく失敗**する(§9)。

このほか playwright-bdd の特殊タグ **`@mode:serial`** が使える。同じスタブへ制御エンドポイント
経由でエラーを注入するシナリオは、これを付けて直列化すること(§9)。

`@slow` と `@destructive` は `npm run test:at:fast` から除外される。日常の回帰確認は fast、
リリース前や AT-19(#945)のクリーンスレート実行では `npm run test:at` を使う。

---

## 5. 実行

前提(スタックの起動、Keycloak のテストユーザー発行、証明書)は
[e2e-testing.md §3](e2e-testing.md) を参照。受け入れテストも同じスタックに対して実行する。

```bash
cd apps/web

npm run test:at            # 全件(@slow / @destructive を含む)
npm run test:at:fast       # @slow と @destructive を除く
npm run test:at:ui         # Playwright Test UI
npm run test:at:clean      # 環境をリセットしてから段階順に全実行(§10)

# タグで絞る(引数はそのまま playwright test へ渡る)
npm run test:at -- --grep @api
npm run test:at -- --grep-invert @stub
npm run test:at -- --grep "@auth|@users"

# ファイル・シナリオ名で絞る
npm run test:at -- --grep "ログイン画面"
```

`npm run test:e2e` は `.feature` と既存 spec の**両方**を実行する。

`bddgen`(`.feature` → `apps/web/.features-gen/`)は Playwright を起動する全 npm script の
先頭で走るため、生成を手動で意識する必要はない。

### シナリオ名はそのままレポートに出る

```
[at-main] › .features-gen/at-main/auth/login.feature.spec.js:6:7 ›
  ログイン › ログイン画面にアクセスするとKeycloakのホスト型ログイン画面へリダイレクトされる @auth
```

つまりシナリオ名は**受け入れ基準の文言そのもの**にする。「正常系1」のような名前を付けない。

---

## 6. 書き方

`apps/web/e2e/features/auth/login.feature`(#926 のサンプル):

```gherkin
# language: ja
@auth
機能: ログイン

  シナリオ: ログイン画面にアクセスするとKeycloakのホスト型ログイン画面へリダイレクトされる
    もし ログイン画面を開く
    ならば Keycloakのホスト型ログイン画面が表示される
```

ステップ定義 `apps/web/e2e/steps/auth.steps.ts`:

```typescript
import { Then, When } from './fixtures';
import { expect } from '../support';

When('ログイン画面を開く', async ({ page }) => {
  await page.goto('/login');
});

Then('Keycloakのホスト型ログイン画面が表示される', async ({ page }) => {
  await page.waitForURL(/\/auth\/realms\/letsblog\//, { timeout: 15000 });
  await expect(page.locator('#username')).toBeVisible();
});
```

使えるキーワードは日本語 Gherkin の `機能:` `背景:` `シナリオ:` `シナリオアウトライン:` `例:`
`前提` `もし` `ならば` `かつ` `しかし`。`# language: ja` は
`playwright.config.ts` の `language: 'ja'` で既定になっているが、明示しておくとエディタの
シンタックスハイライトが効く。

### 原則

[e2e-testing.md §10 の原則](e2e-testing.md)(ログインは helpers 経由、条件付きアサーションを
書かない、フィクスチャは後片付けする、ハードコードされた待機を使わない)は受け入れテストにも
そのまま適用される。加えて:

1. **ステップは利用者の語彙で書く**。`ならば /api/projects が 200 を返す` ではなく
   `ならば プロジェクト一覧に表示される`。HTTP を直接検証するのは `@api` シナリオだけ。
2. **ステップを再利用する**。同じ文言のステップは1回しか定義できない。既存ステップを
   探してから新設する。ドメイン横断で使うものは `common.steps.ts` へ置く。
3. **1シナリオを独立させる**。他のシナリオの実行順序に依存しない。順序に意味がある一連の
   手順は `シナリオアウトライン:` か1つのシナリオにまとめる。

---

## 7. 既存 spec からの移行方針

移行は**ドメイン単位**で、AT-3〜AT-18 の各 Issue が自分の担当範囲について行う。

1. 担当ドメインの受け入れ基準を `.feature` として書く
2. ステップ定義を `steps/<domain>.steps.ts` に実装する
3. **移行元の spec(またはその `test`)を削除する**
4. [e2e-testing.md §2 の表](e2e-testing.md)から削除済みの spec を除く

**二重管理を残さない**のが唯一の規則。`.feature` を足しただけで spec を残すと、
同じ基準が2か所で検証され、片方だけ直された状態に必ずなる。

移行状況:

| spec | 移行先 Issue | 状態 |
| --- | --- | --- |
| `auth-flow.spec.ts` | AT-3 (#929) | **移行完了。spec は削除済み**(`features/auth/` の6ファイル) |
| `accessibility.spec.ts` | AT-18 (#944) | 未 |
| `main-scenario.spec.ts` | AT-6 (#932) | 未 |
| `site-registration.spec.ts` | AT-5 (#931) | 未 |
| `post-creation.spec.ts` | AT-6 (#932) | 未 |
| `image-upload.spec.ts` | AT-10 (#936) | 未 |
| `custom-tag-generation.spec.ts` | AT-12 (#938) | 未 |
| `service-degradation.spec.ts` | AT-17 (#943) | 未 |
| `security.spec.ts` | AT-17 (#943) | 未 |
| `performance.spec.ts` | — | 移行対象外。受け入れ基準ではなく応答時間の閾値検証であり、#915 の判断で「唯一の性能テスト」として Playwright spec のまま維持する |

---

## 8. ブラウザ

受け入れテストは chromium のみで実行する(段階ごとのプロジェクト `at-setup` /
`at-seed` / `at-provision` / `at-main` はいずれも `Desktop Chrome`。§10)。

サイト登録・記事公開・画像生成のようなバックエンド横断のシナリオは、ブラウザを変えても
同じ経路を通る。ブラウザ差が意味を持つのはレンダリング・アクセシビリティ・レスポンシブ、
つまり **AT-18(#944)の範囲だけ**。必要になった時点で AT-18 がブラウザ別のプロジェクトを
追加する(既存 spec 側の `CROSS_BROWSER_SPECS` と同じ考え方)。

---

## 9. 外部依存スタブ

受け入れテストは**外部SaaSに依存しない**(issue #928 / AT-2)。実キーが要る依存は
`docker-compose.e2e-stubs.yml` でまとめてスタブへ置き換える。

```bash
# スタブを起動する(開発スタックへ重ねる)
docker compose -f docker-compose.yml -f docker-compose.e2e-stubs.yml up -d

# システム設定(DB)に残っているLLM/画像生成の上書きを消す(下の「落とし穴」参照)
./scripts/e2e-clear-llm-db-overrides.sh --yes

cd apps/web && npm run test:at
```

### 何をスタブ化しているか

| スタブ | 置き換える依存 | 向き先を決める環境変数 | ホスト公開 |
| --- | --- | --- | --- |
| `llm-stub` | 外部LLM(OpenAI互換 Chat Completions) | `LLM_BASE_URL`(ai / platform) | 18081 |
| `ga-stub` | Google Analytics Data API + OAuth | `GOOGLE_ANALYTICS_DATA_API_BASE_URL`, `GOOGLE_ANALYTICS_OAUTH_TOKEN_URI` | 18082 |
| `adsense-stub` | AdSense Management API + Google OAuth | `ADSENSE_DATA_API_BASE_URL`, `GOOGLE_OAUTH_TOKEN_URI` | 18083 |
| `brave-stub` | Brave Search API | `BRAVE_SEARCH_BASE_URL` | 18084 |
| `image-stub` | OpenAI 画像生成(gpt-image-1) | `IMAGE_LLM_BASE_URL`(platform) | 18085 |
| `github-stub` | GitHub REST API(issues) | `GITHUB_API_BASE_URL`(ai) | 18086 |

実装は `infra/e2e-stubs/<name>/server.js`、共通土台は `infra/e2e-stubs/lib/stub.js`。
`node:22-alpine` にソースをマウントするだけなので、イメージのビルドは要らない。

**スタブ化しないもの**: ComfyUI / PlantUML / draw.io / Penpot / WordPress。
いずれもローカルコンテナとして実物が動くため、実物に対して検証する。

### 決定性

同じ入力には常に同じ応答を返す。タイムスタンプも乱数も含めない
(LLMスタブの `created` は固定値)。だからシナリオは応答の中身をそのままアサートできる。

この約束自体を `features/stubs/external-stubs.feature` が検証している。
スタブを直したら、まずこれを通すこと。

### エラー注入

異常系(401 / 429 / 500 / タイムアウト)は実サービスでは再現できないので、スタブから起こす。
経路は2つあり、**どちらを使うかで並列実行の可否が変わる**。

#### 1. リクエストヘッダ — スタブを直接叩くとき

```
X-E2E-Stub-Force-Status: 429     そのリクエストだけを429にする
X-E2E-Stub-Force-Delay: 30000    そのリクエストだけを遅延させる
```

スタブの状態を変えないので、**並列に走る他のシナリオへ影響しない**。
ヘルパーは `support/stubs.ts` の `forceStatusHeader()` / `forceDelayHeader()`。

#### 2. 制御エンドポイント — サービス越しに呼ばせるとき

実際の異常系シナリオでスタブを呼ぶのはサービスであってテストではないため、
テストはヘッダを差し込めない。事前にスタブへ仕込む。

```bash
curl -XPOST http://127.0.0.1:18081/__control/force -d '{"status":429,"count":1}'
curl -XPOST http://127.0.0.1:18081/__control/reset
curl      http://127.0.0.1:18081/__control/state    # 仕込みと受信件数
```

ヘルパーは `forceStubStatus()` / `forceStubDelay()` / `resetStub()` / `stubRequestCount()`。

> **この経路はスタブ全体の状態を変える。** 同じスタブへ注入するシナリオを並列に走らせると
> 互いの仕込みを奪い合う。使うシナリオには **`@mode:serial`** を付け、同じスタブを触る
> シナリオを複数の `.feature` に散らさないこと。

`stubRequestCount()` は「サービスが実際に外部を呼んだか」の確認に使える。
キャッシュが効いて外部を呼ばなかったのか、呼んで失敗したのかを区別できる。

### 資格情報の不正を再現する

制御エンドポイントを使わず、**特定の値を登録するだけ**で認証失敗を起こせる。
「不正なキーを登録した利用者に何が見えるか」を検証するときはこちらを使う。

| スタブ | 値 | 結果 |
| --- | --- | --- |
| `ga-stub` | サービスアカウントJSONの `client_email` が `invalid@` で始まる | トークン交換が401 |
| `adsense-stub` | 認可コード `e2e-stub-invalid-code` | トークン交換が401 |
| `adsense-stub` | リフレッシュトークン `e2e-stub-invalid-refresh` | トークン交換が401 |
| `brave-stub` | APIキー `e2e-stub-invalid-key` | 401 |
| `github-stub` | トークン `e2e-stub-invalid-token` | 401 |
| `github-stub` | トークン `e2e-stub-readonly-token` | 書き込みが403 |
| `github-stub` | トークン `e2e-stub-ratelimited-token` | 403 + `X-RateLimit-Remaining: 0` |

### 落とし穴: システム設定(DB)が環境変数より優先される

LLM と画像生成の接続設定は「DB(`lbs_platform.system_settings`)に値があればDB、
無ければ環境変数の既定値」という順で解決される(platform-service の `AppSettingService` が正)。

つまり **compose で `LLM_BASE_URL` を差し替えても、システム設定画面で一度でも保存していれば
実サービスへ出ていく**。実キーが入っていれば課金が発生し、入っていなければテストが不可解に落ちる。

```bash
./scripts/e2e-clear-llm-db-overrides.sh          # 消す行を表示するだけ
./scripts/e2e-clear-llm-db-overrides.sh --yes    # 削除して platform を再起動
```

GA / AdSense / Brave / GitHub の資格情報は**プロジェクト単位のDB設定**であって
システム設定ではないため、この問題は起きない。向き先(baseUrl)だけが環境変数で決まる。

> **GAのトークン交換先はサービスアカウントJSONが優先する。** `token_uri` がJSONに書いてあると
> `GOOGLE_ANALYTICS_OAUTH_TOKEN_URI` は無視される。スタブ用のサービスアカウントJSONには
> `token_uri` を**書かないこと**。

### スタブ未起動は「スキップ」ではなく「失敗」

`@stub` が付いたシナリオは、スタブが起動していなければ**明示的なエラーで落ちる**
(`support/stubs.ts` の `requireStubs()`、`steps/stubs.steps.ts` の `Before({ tags: '@stub' })`)。

#843 では LLM が使えない環境で `test.skip` に落とし、その分岐が恒常的に未検証のまま
気づかれずに残った。スタブを起動していないことは環境の不備であって、
検証しなくてよい理由ではない。

### スタブを直したら再起動する

ソースはコンテナへ**マウント**されているが、Node はプロセス起動時に読み込む。
`infra/e2e-stubs/` を編集したら反映のために再起動すること。

```bash
docker compose -f docker-compose.yml -f docker-compose.e2e-stubs.yml restart \
  llm-stub ga-stub adsense-stub brave-stub image-stub github-stub
```

---

## 10. クリーンスレート実行と段階順序

受け入れテストは**毎回まっさらな状態から**実行する(issue #945 / AT-19)。
前のテストが残したデータに依存して通る/落ちるテストを作らないため、また初回セットアップ
(ユーザー0人)やバックアップ/リストアのようにクリーンな状態を要するシナリオを
書けるようにするため。

```bash
source ~/.config/lets-blog-e2e.env    # 合成アカウントの資格情報(リポジトリ外・モード600)
cd apps/web
npm run test:at:clean                 # リセット → 段階順に全実行
```

### 段階

```
reset ─→ at-setup ─→ at-seed ─→ at-provision ─→ at-main ─→ at-destructive
```

| 段階 | 中身 | 担当 |
| --- | --- | --- |
| `reset` | `scripts/reset-acceptance-env.sh --yes`。`globalSetup` が `ACCEPTANCE_RESET=1` のときだけ実行 | AT-19 |
| `at-setup` | `@stage:setup` のシナリオ。初回セットアップ(ユーザー0人 → 最初の管理者) | AT-3 (#929) |
| `at-seed` | `scripts/seed-acceptance-env.sh`。E2E専用の合成アカウントを発行 | AT-19 |
| `at-provision` | `@stage:provision` のシナリオ。**WordPress のプロビジョニング** | AT-5 (#931) |
| `at-main` | 上記以外すべて(`@destructive` を除く) | 各ドメインIssue |
| `at-destructive` | `@destructive` のシナリオ。**最後に、それだけで**実行する | 各ドメインIssue |

### なぜ `@destructive` を別段階にするか(#929)

`@destructive` は「環境の状態を壊す」シナリオである。壊す対象は**共有されている**。
例えば「無効化したユーザーの発行済みトークンが拒否される」は、共通の合成アカウントを
一時的に無効化する。これを他のシナリオと並列に走らせると、同じアカウントでログインしている
無関係なシナリオが巻き添えで落ちる(実測で3件が落ちた)。

Playwright はファイルをまたぐ直列化の手段を持たない(`@mode:serial` は同一ファイル内だけ)。
段階を1つ足して「この段階が走るときは他に誰も走っていない」状態を作るのが、
この構成で表現できる唯一の確実な隔離である。

データを消さなくても、**共有された状態を一時的に壊すなら `@destructive` を付ける**。
ログアウト(Keycloak のSSOセッションを終了させる)のように、消すのはデータではないが
並列実行を壊すものも含む。

段階は Playwright のプロジェクト間 `dependencies` で表現している
([apps/web/playwright.config.ts](../apps/web/playwright.config.ts))。したがって:

- **`--project=at-destructive` を指定するだけでよい。** 依存する前段は Playwright が自動で先に走る。
  `npm run test:at` / `test:at:clean` はこれを指定している。
- **前段が失敗したら後続は実行されない。** Playwright は依存プロジェクトが落ちた場合、
  依存元を「失敗」ではなく**スキップ**として報告する。プロビジョニングが失敗したときに
  大量の失敗が並んで原因が埋もれる、という事態を避けるための設計である。

### 段階タグ

`@stage:setup` / `@stage:provision` は**段階の割り当てにしか使わない**。
付けなければ `at-main` に入る(`@destructive` が付いていれば `at-destructive`)。
§4 のタグ規約(`@slow` 等)とは目的が違うので混ぜないこと。

新しいシナリオを書くとき、これらを付ける必要はほぼ無い。付けるのは
「他の全シナリオより先に成立していなければならない前提」だけである。

### 初回セットアップを二重に定義しない

最初の管理者を作るのは `at-setup` 段階の**シナリオそのもの**(AT-3)である。
シードスクリプトは「ユーザーが0人のまま来た場合」だけ補完として作る。
単一ドメインのテストを回すために `at-setup` を通さず実行したときのための逃げ道であり、
通常の全実行では `setup-status` が `needsSetup: false` を返してスキップされる。

### リセットが消すもの

| 対象 | 内容 |
| --- | --- |
| MySQL | 9スキーマ(`lbs_identity` 他)を drop → create し、サービス再起動で Flyway に再作成させる |
| MySQL(WP) | ManagedWordPress のサイト別DB(`wp_*`) |
| Keycloak | `letsblog` レルムの **`@letsblog.local` ドメインのアカウントだけ** |
| WordPress | `/var/www/html/sites/*` の実体 |
| メディア | 生成画像の保存領域、ComfyUI の output |
| RabbitMQ | 全キューの purge |

`*_test` スキーマ(ホストからの `./gradlew test` 用)には触れない。

スキーマを作り直すと `GRANT` が失われるため、`infra/mysql/init/01-create-service-schemas.sh` を
再実行して権限を張り直す。ここが失敗すると各サービスの Flyway が起動時に落ちるので、
スクリプトはこの失敗で中断する。

> **副次的な効果**: 毎回 drop してから Flyway に再作成させるので、
> 「マイグレーションが空スキーマから通るか」も同時に検証される(#914 の契約テストと同じ性質)。

### 安全装置

`reset-acceptance-env.sh` は**接続先を指定するオプションを持たない**。
コンテナ名(`lbs-mysql` / `lbs-keycloak` / `lbs-wordpress` / `lbs-rabbitmq` / `lbs-media` /
`lbs-comfyui`)とレルム名(`letsblog`)はスクリプト内で `readonly` に固定してある。
`scripts/provision-e2e-keycloak-users.sh` と同じ設計で、共有/本番環境では実行できない。

Keycloak のユーザー削除は **`@letsblog.local` ドメインに限定**する。
このレルムには利用者の実アカウント(`s.tonouchi@gmail.com`)が居るため、
ドメインで区切ることが実装上の保証になっている。ドライラン(`--yes` なし)は
削除対象と**保護対象**の両方を表示するので、実行前に必ず確認すること。

```bash
./scripts/reset-acceptance-env.sh          # ドライラン。何も消さない
./scripts/reset-acceptance-env.sh --yes    # 実行
```

### リセットは自分で検証する

リセットスクリプトは、最後に**成立したことを確かめてから**終わる。
「消したつもり」で終わらせない — リセットが不完全なまま受け入れテストを始めると、
前のデータに依存した結果が出て、しかもそれが分からない。

1. 9スキーマに Flyway 管理テーブル以外のデータが残っていないこと
2. Keycloak に `@letsblog.local` のアカウントが残っていないこと
3. WordPress にサイト実体が残っていないこと
4. `GET /api/auth/setup-status` が gateway 経由で 200 を返すこと

> 4番目は #951 の名残である。gateway の DNS キャッシュが古く、同時再起動で IP が
> 入れ替わると**別のサービスへ転送し続ける**という不具合があった(修正済み)。
> 宛先の取り違えは症状が 401 なので認可の設定を疑ってしまう。この確認を残しておくと、
> 同種の問題が再発したときに「リセットの最後」で止まって気づける。

いずれかが崩れていればスクリプトは非0で終了する。

### 所要時間(2026-09-01 実測)

| 段階 | 実測 |
| --- | --- |
| `reset` | 約 30 秒(うち大半はサービス11本の再起動と healthy 待ち) |
| `at-seed` | 約 8 秒 |
| `npm run test:at:clean` 全体(シナリオ7件) | 約 46 秒 |

毎回フルリセットしても、実行時間の支配項はシナリオ本体であって初期化ではない。

**リセットを省略する選択肢は取らない**
(それをやると「前のテストの残骸に依存して通るテスト」が戻ってくる)。
時間が問題になったら、段階の並列化や不要なコンテナの停止で対処すること。

### 前段が失敗したときの見え方

```
$ npm run test:at         # 資格情報を渡さずに実行した場合
  1 failed
    [at-seed] › e2e/stages/seed.setup.ts:20:5 › 受け入れテスト環境にシードを投入する
  6 did not run
```

後続は「6 failed」ではなく **`6 did not run`** になる。原因が1行で読める。

### Keycloak の合成アカウントは profile を埋めないと認証できない

このレルムでは required action の `VERIFY_PROFILE` が有効になっている。
`firstName` / `lastName` が空のユーザーは、ブラウザのログインでは補完画面が出るだけだが、
**パスワードグラント(直接付与)では `Account is not fully set up` で失敗する**。
`apps/web/e2e` の `fetchAccessToken()` はパスワードグラントを使うため、ここが埋まっていないと
API直叩きのテストが1件も動かない。

identity-service の `KeycloakAdminClient#createUser` は `firstName`/`lastName` を送らないので、
`POST /api/auth/setup` や `POST /api/users` で作られたアカウントは必ずこの状態になる。
`scripts/provision-e2e-keycloak-users.sh` と `scripts/seed-acceptance-env.sh` は、
パスワード設定に続けてプロフィールを補完する(#945)。

従来これが表面化しなかったのは、既存の合成アカウントが以前の経緯でプロフィールを
持っていたためで、環境をまっさらにして作り直したときに初めて露見した。

### `E2E_DB_CLEANUP` はもう受け入れテストには要らない

受け入れテストは実行の**前**に全部消してから始めるので、終了時の後片付けは不要である。
むしろ残しておいたほうが失敗の調査ができる。

`global-teardown.ts` と `E2E_DB_CLEANUP` を残しているのは、`.feature` へ未移行の
Playwright spec(`apps/web/e2e/*.spec.ts`)が「既存データを壊さない一意なフィクスチャ」という
**逆の前提**で書かれており、その孤児行の掃除には依然として必要だから(#765)。
全 spec の移行が終わった時点で teardown ごと削除する(§7)。

---

## 11. 参考

- [ACCEPTANCE_CRITERIA.md](ACCEPTANCE_CRITERIA.md) — 受け入れ基準カタログ(機能IDと検証状況)
- `docker-compose.e2e-stubs.yml` / `infra/e2e-stubs/` — 外部依存スタブ(§9)
- [e2e-testing.md](e2e-testing.md) — スタック起動、Keycloak プロビジョニング、テストデータ、トラブルシューティング
- [TEST_DOCUMENTATION.md](TEST_DOCUMENTATION.md) — テスト全体の階層
- [playwright-bdd](https://vitalets.github.io/playwright-bdd/)
- Epic #925 / AT-0 #926
