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
[web/playwright.config.ts](../web/playwright.config.ts) が持つ次の資産をすべて再実装することになる。

- `globalSetup` — 全サービスの healthy 待ちと公開URL/Keycloak への疎通確認
- `use.ignoreHTTPSErrors` — reverse-proxy の自己署名証明書
- trace / video / screenshot の失敗時取得
- ブラウザ別プロジェクト分割、`retries`、ワーカー制御

`playwright-bdd` は `.feature` を **Playwright のテストへ変換する**だけなので、
ランナーは Playwright のまま。上記をそのまま使える。

```
.feature ──(bddgen)──> web/.features-gen/**/*.spec.js ──(playwright test)──> 実行
```

生成物 `web/.features-gen/` はコミットしない。`.gitignore` / `eslint.config.mjs` /
`tsconfig.json` の3か所で除外している(#848 と同型の再発防止。**除外を足すときは3か所とも**)。

---

## 3. 配置規約

| パス | 内容 |
| --- | --- |
| `web/e2e/features/<domain>/*.feature` | 受け入れ基準。日本語の Gherkin で書く |
| `web/e2e/steps/fixtures.ts` | 全ステップ定義が共有する `test` インスタンスと `Given/When/Then` |
| `web/e2e/steps/<domain>.steps.ts` | ドメイン固有のステップ定義 |
| `web/e2e/steps/common.steps.ts` | ドメイン横断の共通ステップ(ログイン済み状態など) |
| `web/e2e/support/index.ts` | 既存 `web/e2e/helpers.ts` の再エクスポート |
| `web/e2e/*.spec.ts` | 移行前の既存 Playwright spec(§7) |

`<domain>` は AT Issue の区切りに合わせる:
`auth` / `users` / `projects` / `posts` / `bulk` / `ai` / `plans` / `media` / `diagrams` /
`content` / `analytics` / `system` / `logs` / `extension` / `cross-cutting`。

### support/ を「移設」しないこと

`helpers.ts` は移行前の13 spec が依存している。移行が終わるまでは spec とステップ定義の
両方から使われるため、`support/index.ts` は helpers を**再エクスポートするだけ**にしてある。
ステップ定義は `../support` を、既存 spec は `./helpers` を参照する。
全 spec の移行が終わった時点で helpers の実体を `support/` へ移せばよい。

### フィクスチャを足すとき

`web/e2e/steps/fixtures.ts` の `test` を拡張する。ステップ定義ファイルごとに
`createBdd()` を呼び直すと、フィクスチャの型が食い違って生成時に落ちる。

---

## 4. タグ規約

タグはシナリオ(または `機能:`)に付ける。Playwright のテストタグへ変換されるため、
`--grep` / `--grep-invert` でそのまま絞り込める。

| タグ | 意味 | 付ける基準 |
| --- | --- | --- |
| `@slow` | 分単位で時間がかかる | ComfyUI の実生成、WordPress への実公開、サイトのプロビジョニング |
| `@destructive` | 環境の状態を壊す | 一括削除、バックアップ/リストア、ユーザー0人状態の再現。**前後で復旧すること** |
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
cd web

npm run test:at            # 全件(@slow / @destructive を含む)
npm run test:at:fast       # @slow と @destructive を除く
npm run test:at:ui         # Playwright Test UI

# タグで絞る(引数はそのまま playwright test へ渡る)
npm run test:at -- --grep @api
npm run test:at -- --grep-invert @stub
npm run test:at -- --grep "@auth|@users"

# ファイル・シナリオ名で絞る
npm run test:at -- --grep "ログイン画面"
```

`npm run test:e2e` は `.feature` と既存 spec の**両方**を実行する。

`bddgen`(`.feature` → `web/.features-gen/`)は Playwright を起動する全 npm script の
先頭で走るため、生成を手動で意識する必要はない。

### シナリオ名はそのままレポートに出る

```
[bdd-chromium] › .features-gen/auth/login.feature.spec.js:6:7 ›
  ログイン › ログイン画面にアクセスするとKeycloakのホスト型ログイン画面へリダイレクトされる @auth
```

つまりシナリオ名は**受け入れ基準の文言そのもの**にする。「正常系1」のような名前を付けない。

---

## 6. 書き方

`web/e2e/features/auth/login.feature`(#926 のサンプル):

```gherkin
# language: ja
@auth
機能: ログイン

  シナリオ: ログイン画面にアクセスするとKeycloakのホスト型ログイン画面へリダイレクトされる
    もし ログイン画面を開く
    ならば Keycloakのホスト型ログイン画面が表示される
```

ステップ定義 `web/e2e/steps/auth.steps.ts`:

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
| `auth-flow.spec.ts` | AT-3 (#929) | 1シナリオのみ移行済み(#926 のサンプル) |
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

受け入れテストは `bdd-chromium` プロジェクト(chromium)のみで実行する。

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

cd web && npm run test:at
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

実装は `e2e-stubs/<name>/server.js`、共通土台は `e2e-stubs/lib/stub.js`。
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
`e2e-stubs/` を編集したら反映のために再起動すること。

```bash
docker compose -f docker-compose.yml -f docker-compose.e2e-stubs.yml restart \
  llm-stub ga-stub adsense-stub brave-stub image-stub github-stub
```

---

## 10. 実行順序とクリーンスレート

受け入れテストは**毎回まっさらな状態から**実行する。DBの初期化・シード・段階実行
(WordPress のプロビジョニングを先行させる等)は **AT-19(#945)** が整備する。
本ドキュメントのタグ規約はその段階定義と整合させること。

---

## 11. 参考

- [ACCEPTANCE_CRITERIA.md](ACCEPTANCE_CRITERIA.md) — 受け入れ基準カタログ(機能IDと検証状況)
- `docker-compose.e2e-stubs.yml` / `e2e-stubs/` — 外部依存スタブ(§9)
- [e2e-testing.md](e2e-testing.md) — スタック起動、Keycloak プロビジョニング、テストデータ、トラブルシューティング
- [TEST_DOCUMENTATION.md](TEST_DOCUMENTATION.md) — テスト全体の階層
- [playwright-bdd](https://vitalets.github.io/playwright-bdd/)
- Epic #925 / AT-0 #926
