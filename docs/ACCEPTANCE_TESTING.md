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
`tsconfig.json` / `jest.config.ts` の4か所で除外している(#848 と同型の再発防止。
**除外を足すときは4か所とも**)。

前3者は `.features-gen` を名指しで除外する。`jest.config.ts` だけは形が違い、
`testMatch` で**拾う対象を列挙する**ことで除外する(既定の `testMatch` は rootDir 全体に
及び、生成物 `*.feature.spec.js` を拾ってしまう)。

`testPathIgnorePatterns` に足さないのは、その追加を `.claude/hooks/guard.py` が
「テストの握りつぶし」としてブロックするためで、ガードはこのケース(テストではなく
別ランナーの生成物の除外)を区別しないからである(#994)。

**`roots: ['<rootDir>/src']` で範囲を狭める案は採らない。** next.config.ts は `src/` の外に
あるプロダクションコードで(#984)、その単体テスト `next.config.test.ts` も
`apps/web/` 直下にあるため、`src/` に閉じるとこの1スイートが黙って実行されなくなる
(実測でスイート数 32 → 31、テスト数 191 → 189。しかも出力は緑のまま)。

この4か所が揃っていることは `scripts/test_bddgen_output_is_excluded.py` が検査する。
漏れは**生成物が存在するときにしか現れない**ため、`bddgen` を走らせていない作業ツリーや
クリーンな CI では緑になり、レビューでも気づけない。実際 #994 は `jest.config.ts` の
漏れで、`bddgen` の後の `npm run test` が「15 suites failed」になっていた(単体テストは
189件すべて通っているのに、である)。

```bash
python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'
```

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

### VSCode拡張だけは別のランナーを使う(#942 / AT-16)

拡張の受け入れテストはブラウザを一切使わず、拡張自身の `apiClient` / `httpClient` を
Node から直接呼ぶ。Playwright を持ち込む理由が無いため、拡張が既に使っている jest の上で
`.feature` を実行する最小のランナーを置いてある。**記法・タグの意味・原則はこの文書と同じ**。

| パス | 内容 |
| --- | --- |
| `apps/extension/e2e/features/<domain>/*.feature` | 拡張の受け入れ基準(日本語 Gherkin) |
| `apps/extension/e2e/steps/<domain>.steps.ts` | ステップ定義 |
| `apps/extension/e2e/support/gherkin.ts` | `.feature` → jest への変換とタグ絞り込み |

```bash
cd apps/extension
npm run test:at          # 全件
npm run test:at:fast     # @slow / @destructive を除く
```

詳細は [apps/extension/e2e/README.md](../apps/extension/e2e/README.md)。
UI操作(コマンドパレット・Webview・キーバインド)は自動化せず、
[apps/extension/MANUAL_ACCEPTANCE_CHECKLIST.md](../apps/extension/MANUAL_ACCEPTANCE_CHECKLIST.md)
で人が確認する。

`<domain>` は AT Issue の区切りに合わせる:
`auth` / `users` / `projects` / `posts` / `bulk` / `ai` / `plans` / `media` / `diagrams` /
`content` / `analytics` / `system` / `logs` / `extension` / `cross-cutting`。

このほか、製品ではなく**受け入れテストの土台**を検証するものが2つある。
土台が壊れると、それに乗るシナリオが理由の分からない形で落ちるため、
製品のドメインには混ぜず別に置く。

| パス | 何を固定するか |
| --- | --- |
| `features/stubs/` | 外部依存スタブの決定性とエラー注入(#928 / AT-2、§9) |
| `features/harness/` | Playwright のブラウザ前提確認の判定と文面(#1045、[e2e-testing.md §3.3](e2e-testing.md)) |

どちらも `@api` で書く。ブラウザを起動できないホストでも土台の検証だけは回せる必要がある。

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
| `@stub` | 外部依存スタブの起動が前提 | LLM / GA / AdSense / Brave Search / OpenAI画像生成 / GitHub / ComfyUI(§9) |
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

ホストの 80/443 を他のプロセスが占有している場合は、先に §12 を読むこと。
その状態では global-setup の疎通確認で全シナリオが始まらない。

**Playwright のブラウザは初回に自分で入れる**([e2e-testing.md §3.3](e2e-testing.md)、issue #1045)。
入っていないと global-setup の前提確認で落ち、シナリオは1本も実行されない。
ブラウザ本体は `cd apps/web && npm run playwright:install`(root 不要)、
それが依存する OS 共有ライブラリは `sudo npx playwright install-deps`(**root が要る**ので
自動実行しない)。どちらが足りないかは前提確認の失敗メッセージが示す。

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
| `accessibility.spec.ts` | AT-18 (#944) | **移行完了。spec は削除済み**(`features/ui-quality/accessibility.feature`。旧`Identify accessibility violations for review`は合否を決めない調査用テストだったため移行対象から除外) |
| `main-scenario.spec.ts` | AT-6 (#932) | 未 |
| `site-registration.spec.ts` | AT-5 (#931) | 未 |
| `post-creation.spec.ts` | AT-6 (#932) | 未 |
| `image-upload.spec.ts` | AT-10 (#936) | **移行完了。spec は削除済み**(`features/media/image-gallery.feature`) |
| `custom-tag-generation.spec.ts` | AT-12 (#938) → AT-18 (#944) | **移行完了。spec は削除済み**。レスポンシブテスト1件(モバイル幅でのカスタムタグ生成フォーム操作)は AT-12 (#938) が移行先の判断を AT-18 (#944) へ委ねていた。AT-18 は横断的なレスポンシブ検証(`features/ui-quality/responsive.feature` › モバイル幅で主要な操作(ナビゲーション・フォーム送信)ができる、プロジェクト作成フォームで検証)を既に持っており、LLMバックエンド固有の生成フローの再検証はAT-18のスコープ外(個別機能の振る舞い)と判断し、個別移行はせず削除した |
| `service-degradation.spec.ts` | AT-17 (#943) | **移行完了。spec は削除済み**(`features/cross-cutting/service-degradation.feature`) |
| `security.spec.ts` | AT-17 (#943) | 未。ただし**カスタムタグ領域の5件は AT-12 (#938) が移行済み**(`features/custom-tag/generation.feature`・`templates.feature`)。残る CSRF・SQLインジェクション・入力サニタイズは AC-XC-008〜010 で #943 の担当 |
| `performance.spec.ts` | AT-12 (#938) | **移行完了。spec は削除済み**(`features/custom-tag/performance.feature`)。#915 は「唯一の性能テストとして spec のまま維持する」と判断していたが、#938 の受け入れ基準が2つの閾値を `.feature` として要求したため、そちらが新しい判断になる。「Ollamaレスポンス時間が10秒以内であること」だけは移行先を持たせずに削除した(理由は同 feature の冒頭) |

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

`comfyui`(実機の画像生成コンテナ)は `profiles: ["gpu"]` を持つため(issue #1066)、
GPUの無いホストでもこの `up -d` は中断しない。画像生成の受け入れシナリオは
`comfyui-stub` が受けるので、GPUの無いホストでもE2Eは通る。GPUを持つホストで実機の
`comfyui` も併せて起動したい場合は `.env` の `COMPOSE_PROFILES=gpu` を有効にする。

### gateway の `api-global` 枠を受け入れテスト用に引き上げる(issue #1132)

`docker-compose.e2e-stubs.yml` は `gateway` サービスにも重なっており、
`API_RATE_LIMIT_REQUESTS` を本番既定値(100 req/分)より引き上げている。

`@api` タグのシナリオ(約150本)は nginx 経由(`https://localhost`)で gateway を叩くため、
gateway からは**ブラウザ経由の外部トラフィックと区別が付かない**。`api-global` は
クライアント単位に分割されているが(issue #749)、受け入れテストの実行元は常に1台の
Playwright プロセスなので、分割してもスイート全体が「1クライアント」のままである。
結果として、フィクスチャ構築の呼び出し(`POST /api/projects` 等、シナリオ本来の検証とは
無関係)だけで本番既定の100req/分を使い切り、無関係なシナリオが429で落ちる
(詳しい実測とバケット選定の根拠は `docs/API_RATE_LIMITING.md` の
「Acceptance-test override (issue #1132)」を参照)。

**本番の既定値は変えていない。** この上書きは `docker-compose.e2e-stubs.yml` の
`gateway:` サービスにだけ効き、`docker-compose.yml` 単体(本番相当の構成)には影響しない。

### gateway の `upload-endpoint` 枠を受け入れテスト用に引き上げる(issue #1286)

`upload-endpoint`(画像生成/アップロード、既定 **1時間に10回**、プロセス全体で1バケット)は
#1132 では対象外だった。`image-generation.feature` 冒頭の内訳どおり、`@slow` を含む全件実行
(`test:at` / `test:at:clean`)の消費はちょうど12で、既定値の10を2つ超える。`@slow` を除いた
通常実行(`test:at:fast`)は10でぴったり枠に収まるが、全件実行では必ずどれか2シナリオが
429で落ちる。1時間以内の再実行も同じ(まだ明けていない)枠を使うため、同じ問題が起きる。

**本番の既定値は変えていない。** この上書きも `docker-compose.e2e-stubs.yml` の `gateway:`
サービスにだけ効く。値の根拠(消費の内訳とヘッドルームの計算)は `docs/API_RATE_LIMITING.md`
「Acceptance-test override (issue #1286)」を参照。

### 何をスタブ化しているか

| スタブ | 置き換える依存 | 向き先を決める環境変数 | ホスト公開 |
| --- | --- | --- | --- |
| `llm-stub` | 外部LLM(OpenAI互換 Chat Completions) | `LLM_BASE_URL`(ai / platform) | 18081 |
| `ga-stub` | Google Analytics Data API + OAuth | `GOOGLE_ANALYTICS_DATA_API_BASE_URL`, `GOOGLE_ANALYTICS_OAUTH_TOKEN_URI` | 18082 |
| `adsense-stub` | AdSense Management API + Google OAuth | `ADSENSE_DATA_API_BASE_URL`, `GOOGLE_OAUTH_TOKEN_URI` | 18083 |
| `brave-stub` | Brave Search API | `BRAVE_SEARCH_BASE_URL` | 18084 |
| `image-stub` | OpenAI 画像生成(gpt-image-1) | `IMAGE_LLM_BASE_URL`(platform) | 18085 |
| `github-stub` | GitHub REST API(issues) | `GITHUB_API_BASE_URL`(ai) | 18086 |
| `comfyui-stub` | ComfyUI(画像生成。**枚数と seed の検証だけ**) | `COMFYUI_BASE_URL`(platform / media) | 18087 |

実装は `infra/e2e-stubs/<name>/server.js`、共通土台は `infra/e2e-stubs/lib/stub.js`。
`node:22-alpine` にソースをマウントするだけなので、イメージのビルドは要らない。

### `working_dir` はマウント先(`/app`)にしない

スタブは `./infra/e2e-stubs` を `/app` へ読み取り専用でマウントするが、`working_dir` は
**`/`** であり、起動は `node /app/<name>/server.js` と絶対パスで書く(#1000)。

ヘルスチェックは `docker exec` と同じ経路で走り、その cwd はコンテナの `WorkingDir` に
なる。`healthcheck:` には `docker exec -w` に当たる指定が無いので、cwd を選べるのは
`working_dir` だけである。**runc 1.4.0**(Ubuntu 26.04 / Docker 29.1.3)は exec の cwd が
バインドマウント経由でマウント名前空間のルート外を指す場合、プロセスの起動そのものを拒否する。

```
OCI runtime exec failed: exec failed: unable to start container process:
current working directory is outside of container mount namespace root
-- possible container breakout detected
```

拒否されるのは**プロセスが動き出す前**なので、`test:` の中に `cd /` を書いても効かない。
`working_dir: /app` のままだとスタブ6本が起動直後から恒常的に `unhealthy` になり、
`depends_on: condition: service_healthy` で待つ `ai` / `platform` / `analytics` が起動を
完了できず、上の `docker compose ... up -d` がそこで止まる。
`scripts/verify-clean-volume-boot.sh`(`wait-for-stack-healthy.sh --all`)も必ずタイムアウトする。

この拒否は runc のバージョンに依存する(1.4.3 では起きない)。**動く環境があることは
この組み合わせが安全である根拠にならない**ため、compose の宣言そのものを
`scripts/test_compose_healthcheck_cwd.py` が検査する。同じ理由で `web`
(イメージの `WORKDIR` が `/app`、そこへ `./apps/web` をマウント)も `working_dir: /` にしてある。

その帰結として、**`docker compose exec` は `/` で始まる**。コンテナの中で
`package.json` のあるディレクトリを前提にするコマンドを流すときは cwd を明示すること。

```bash
docker compose exec -w /app web sh      # web は npm を /app で動かす
docker exec -w /app lbs-e2e-llm-stub sh # スタブのソースは /app:ro
```

**スタブ化しないもの**: PlantUML / draw.io / Penpot / WordPress。
いずれもローカルコンテナとして実物が動くため、実物に対して検証する。

### ComfyUI だけは実機とスタブを併用する

ComfyUI は**実機とスタブの両方を使う**(#1106 / #936、2026-09-07 の方針決定)。
どちらか一方への置き換えではないので、シナリオを書くときは下の切り分けに従うこと。

| 使うもの | 何を検証するか | シナリオ | タグ | 前提 |
| --- | --- | --- | --- | --- |
| 実機 `lbs-comfyui` | 実際に画像が生成できること、生成パラメータが記録に残ること、チェックポイントの一覧・導入・削除 | `features/media/image-generation.feature`、`features/media/comfyui-checkpoints.feature`(#936(AT-10)の 1・2 と 12〜14) | `@slow` | **GPU 必須**。無ければ明示的に失敗する(暗黙スキップにしない) |
| `comfyui-stub` | batch size の枚数(1〜16)、リピートごとに seed が変わること、seed が生成画像に残ること、`/api/ai/image-options` の一覧 | `features/stubs/comfyui-stub.feature`、および #1101 / #1102 / #1103 / #1105 の枚数・seed のシナリオ | `@stub` | GPU 不要。**GPU 非搭載環境でも通る** |

分ける理由は実行時間と決定性である。実機の生成は1枚あたり数十秒かかるため、
batch size 16 の枚数検証や batch count のリピート検証を実生成で行うと現実的な時間に
収まらない。逆に、実際に絵が出ることはスタブでは分からない。

**スタブは実機のシナリオを置き換えない。** `@slow` のシナリオが `COMFYUI_BASE_URL` を
スタブへ向けたまま通ってしまうと、「実機で生成できること」が誰も検証しない状態になる。
スタブを使う構成(`docker-compose.e2e-stubs.yml`)で `@slow` を回さないこと。

スタブが再現しないもの: 画像の見た目、モデル固有の挙動、生成時間、VRAM の実際の解放。
`/view` が返すのは 1×1 の PNG 固定である(`openai-image` スタブと同じバイト列)。

#### `@requires-gpu`: リリース検証だけは除外する(#1318、利用者の決定 2026-09-15)

GPU の無いホスト(実機 `lbs-comfyui` を起動できない)でも `scripts/release-verify-tag.py`
によるリリース検証(`test:at:clean`)がゼロ許容で通せるよう、実機 GPU が無いと必ず失敗する
シナリオ(`comfyui-checkpoints.feature` の導入・削除の2つ)には `@requires-gpu` を付けている。
一覧取得のシナリオと `image-generation.feature` は GPU の無いホストでも(スタブを相手に)通るため
対象にしていない。ただしスタブで通っている以上、これらは実機を検証していない — その是正は
#1318 の Out of Scope として残っている。

- **リリース検証(`release-verify-tag.py`)だけが除外する。** `web-test-at-clean` 手順は
  `AT_EXCLUDE_REQUIRES_GPU=1` を設定して `test:at:clean` を実行し、
  `apps/web/playwright.config.ts` の `at-main` / `at-destructive` の生成時タグ式
  (`defineBddProject` の `tags`)がこの環境変数を見て `and not @requires-gpu` を足す。
  `--grep-invert` では実現できない — `test:at:clean` が選ぶ `at-destructive` の
  対象シナリオは依存プロジェクト `at-main` に属し、Playwright の `--grep` /
  `--grep-invert` は依存プロジェクトのテストを絞り込まないため(#1318 Readiness評価で実測)。
- **`test:at:clean` / `test:at` を手で実行したときは対象外にしない。** 環境変数を設定しない
  限りタグ式は従来どおりで、GPU の無いホストでは方針(上表「GPU 必須。無ければ明示的に
  失敗する」)どおり明示的に失敗する。暗黙のスキップではない。
- 除外したシナリオ(パスとシナリオ名)は `.feature` の `@requires-gpu` から機械的に作り、
  リリース検証の実行ログとタグの注釈(`build_tag_message()`)の両方に記録する。
- `CLAUDE.md` → Test-First Implementation → **Never skip a test** が禁じる
  `--grep-invert` 等による除外の**唯一の例外**である(本決定、#1318)。

#### チェックポイント導入シナリオが使うモデル(#936)

`comfyui-checkpoints.feature` の導入シナリオは **283KB の safetensors**
(`hf-internal-testing/tiny-sd-pipe` の `text_encoder/model.safetensors`。URL は
`apps/web/e2e/steps/media.steps.ts` の `TINY_CHECKPOINT_URL`)を落とす。
#936 の当初方針は実生成と同じ SDXL base(約6.9GB)だったが、**導入シナリオが確かめるのは
ダウンロードと配置が成立することだけ**であり、大きさは検証内容に関係しない。

所要時間の実測(2026-09-07、この開発ホスト): ダウンロード開始からジョブ完了まで **9.5 秒**。
同じ日に SDXL base 相当の 5.7MB を試したときは回線が 4KB/s まで落ちて 20 分見込みになり、
Playwright の既定タイムアウトを超えた。フィーチャに `@timeout:600000` を付けてあるのは
そのためで、モデルの大きさではなく回線の遅さに備えるものである。

導入したファイルは `@media` の `After` が必ず削除する。`comfyui_models` ボリュームは
ゼロ構築でも**保全される**(§10)ため、消さないと実行のたびに溜まる。

投入したワークフローの seed と batch size は制御エンドポイントから読める。
「リピートごとに seed が変わる」(#1102)ことは、生成された画像だけを見ても分からない。

```bash
curl -s http://127.0.0.1:18087/__control/state | jq '.prompts[-1] | {seed, batchSize}'
```

#### comfyui-stub のシナリオを回す前に DB 上書きを消す

**`lbs_platform.system_settings` に `comfyui_base_url` の行があると、overlay の
`COMFYUI_BASE_URL=http://comfyui-stub:8080` は黙って無視され、スタブへ向かない。**
`AppSettingService.resolve()` が DB 優先で、`comfyui_base_url` は管理APIから保存できる
設定キーだからである(下の「落とし穴」と同じ仕組み。#1106)。

`scripts/e2e-clear-llm-db-overrides.sh` はこの行も消す(#1106 で `KEYS` に追加した)。
comfyui-stub を使うシナリオの前に実行すること。

```bash
./scripts/e2e-clear-llm-db-overrides.sh --yes
```

GPU の無いホストでは、この行が残っていると存在しない実 ComfyUI へ向かうため、
スタブは healthy なのにシナリオだけが不可解に落ちる。

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
>
> **ただし `@mode:serial` だけでは、注入しない側(別ファイルの通常系シナリオ)との衝突は
> 防げない。** 下の「`@mode:serial` は同一ファイル内しか直列化しない」を参照。
> `llm-stub` はこれに対処済み(`@stub-isolation:llm`)。GA/AdSenseスタブは
> 同型のリスクが確認済みだが未対処(issue #1188、下記)。

`stubRequestCount()` は「サービスが実際に外部を呼んだか」の確認に使える。
キャッシュが効いて外部を呼ばなかったのか、呼んで失敗したのかを区別できる。

#### `@mode:serial` は同一ファイル内しか直列化しない(issue #1188)

`playwright.config.ts` は `fullyParallel: true` で、`@mode:serial`
(playwright-bddの`describe.configure`。`node_modules/playwright-bdd/dist/generate/
specialTags.js` の `extractMode`)は**同一 `.feature` ファイル内**のシナリオしか
直列化しない。これは、注入する側同士の衝突は防ぐが、**注入しない側**(通常系で同じ
スタブを呼ぶ別ファイルの既存シナリオ)との衝突は防がない。

再現手順(2026-09-08、issue #1149の実装中に発見):

```bash
cd apps/web
rm -rf .features-gen && npx bddgen
npx playwright test --project=at-main --no-deps \
  --grep "AI生成(下書き|下書き・壁打ち)|AI生成の異常系"
```

- `ai/generation.feature` › セクション生成の壁打ち再生成 → 502(注入した429を横取り)
- `ai/resilience.feature` › 429シナリオ → 200(注入前に別ファイルのリクエストが消費)

2026-09-19のリリース検証では、**注入していない**シナリオ(`ai/resilience.feature`の
「LLM接続設定が未設定/不正なとき…」、`stubRequestCount('llm')` の前後比較のみ)も
同じ原因で失敗した(受信件数が要求前後で 15 → 16 に変化)。原因は`@mode:serial`の
file-scopedな限界そのものであり、注入の有無を問わず「同じスタブへ触れるシナリオが
別ファイルで並列に走る」こと自体が問題である。

**LLMスタブへの対処**: `llm-stub` へ実トラフィックを送る、または制御エンドポイントで
その共有状態(`forced` / `requests`)を仕込む/読む全シナリオに `@stub-isolation:llm`
タグを付け、`apps/web/playwright.config.ts` の専用プロジェクト `at-llm-exclusive`
(`workers: 1`)へ集約した。対象: `ai/generation.feature`・`ai/resilience.feature`・
`ai/model-selection.feature`・`ai/review-step-suggestions.feature`・
`ai/tag-and-proofread.feature`・`ai/web-search.feature`・`stubs/external-stubs.feature`
(いずれもLLMスタブへ実際にリクエストを送る)。`ai/authorization.feature`・
`ai/review-step-model-settings.feature` は設定CRUDのみで実際の生成呼び出しが無いため
対象外、`ai/generation-job.feature`と`platform/system-settings.feature`のLLM切替
シナリオは既存の`@destructive`で既に単独実行されており対象外(いずれもコメントに
根拠を記載)。

`at-llm-exclusive` は `at-provision` にのみ依存し、`at-main` とは依存関係が無いため
**`at-main`の無関係なシナリオとは並列に走る** — スイート全体の実行時間はほぼ増えない。
増えるのはこのレーン内のシナリオ同士が直列化される分だけで、`at-main`全体の所要時間
より短いことが実質コストゼロの前提になる(実測値は計測していない。桁が変わるほど
大きくなるとは考えにくい、という設計時の判断)。`at-destructive`は`at-main`と
`at-llm-exclusive`の両方の完了を待つ(`dependencies: ['at-main', 'at-llm-exclusive']`)
よう変更した — `at-destructive`の「他に誰も走っていない」前提を保つため。

`test:at` / `test:at:clean` は `--project=at-destructive` を起動するため、依存関係
(`at-setup → at-seed → at-provision → { at-main, at-llm-exclusive } → at-destructive`)
経由で `at-llm-exclusive` も自動的に実行される — 起動コマンドの変更は不要。
単一ファイルだけを実行したい場合は対象プロジェクトが変わる点に注意:

```bash
# 変更前(このIssueより前): resilience.feature は at-main の一部だった
npx playwright test --project=at-main --no-deps --grep "AI生成の異常系"

# 変更後: llm-stubに触れるファイルは at-llm-exclusive に移った
npx playwright test --project=at-llm-exclusive --no-deps --grep "AI生成の異常系"
```

**採らなかった案**: スタブ側にテストごとの分離トークンを持たせる案(制御エンドポイントの
状態をテスト単位でキーイングする)は、実際にスタブを呼ぶのはテストではなくサービス
(gateway → ai-service)であるため、相関IDをサービス境界を越えて伝播させるプロダクション
コードの変更が要る。このIssueのScope(`playwright.config.ts` の変更、または
`infra/e2e-stubs/lib/stub.js` の注入機構そのもの)を超えるため採らなかった。

**GA/AdSenseスタブは同型のリスクが実在するが未対処**(issue #1188のAcceptance
Criteriaは確認のみを要求): `analytics/report-failures.feature`(`@mode:serial`、
`ga-stub` / `adsense-stub` へ制御エンドポイントで注入)と、`analytics/dashboard-report.feature`
(`@mode:serial`無し、同じスタブへ通常系のリクエストを送る)が同じ形の関係にある。
`llm-stub`と同じ対処(専用の直列プロジェクトへの集約)は技術的に転用できるはずだが、
本Issueのスコープではない。対処はissue #1372で追跡する。

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

**`comfyui_base_url` も同じ扱いである**(#1106)。管理APIから保存できる設定キーなので、
行が入ると `COMFYUI_BASE_URL` の差し替えが効かない。上のスクリプトが消す
(`KEYS` に入っている)。新しい向き先や資格情報のキーを `AppSettingService` に足したら、
`KEYS` にも足すこと — 足し忘れは `scripts/test_e2e_clear_db_overrides.py` が検出する。

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
  llm-stub ga-stub adsense-stub brave-stub image-stub github-stub comfyui-stub
```

---

## 10. クリーンスレート実行と段階順序

受け入れテストは**毎回まっさらな状態から**実行する(issue #945 / AT-19、#965)。
前のテストが残したデータに依存して通る/落ちるテストを作らないため、また初回セットアップ
(ユーザー0人)やバックアップ/リストアのようにクリーンな状態を要するシナリオを
書けるようにするため。

実行順序は **「全撤去 → ゼロから構築 → テスト」** である。利用者の方針(2026-09-01)は

> 1. システムを0から構築してスタートする
> 2. テストを行う
> 3. テストのために作成した一切のものを削除する

の3つで、**3 はテスト終了後の後片付けではなく、次回実行の最初のステップ**として行う。
終了時に何も消さないので、失敗の調査は実行後のスタックに対してそのまま行える(#945 の判断)。

```bash
source ~/.config/lets-blog-e2e.env    # 合成アカウントの資格情報(リポジトリ外・モード600)
cd apps/web
npm run test:at:clean                 # 全撤去+ゼロ構築 → 段階順に全実行
```

`npm run test:at` / `npm run test:at:fast` は**既存スタックに対して回す高速経路**であり、
撤去も構築も行わない。ゼロ構築を通るのは `test:at:clean` だけである。

### 段階

```
全撤去+ゼロ構築 ─→ at-setup ─→ at-seed ─→ at-provision ─→ at-main ─→ at-destructive
```

| 段階 | 中身 | 担当 |
| --- | --- | --- |
| 全撤去+ゼロ構築 | `scripts/rebuild-acceptance-env.sh --yes`。`globalSetup` が `ACCEPTANCE_RESET=1` のときだけ実行 | AT-19 / #965 |
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

### 破棄するボリューム / 保全するボリューム

ゼロ構築(`scripts/rebuild-acceptance-env.sh`)は、**消す対象を列挙して個別に消す**のではなく
**ボリュームごと破棄する**。列挙方式には「列挙漏れが起きたときに気づく手段が無い」という
弱点があり(#965 Problem)、ボリューム破棄はその弱点を構造的に消す。

| 破棄するボリューム | 中身 | 破棄する理由 |
| --- | --- | --- |
| `lets_blog_server_mysql_data` | 9スキーマ・`wp_*`・`*_test`・MySQL ユーザーと `GRANT` | 初期化スクリプトが本来の経路で走り、`GRANT` を手で張り直す回避が要らなくなる |
| `lets_blog_server_keycloak_postgres` | `letsblog` レルム定義・クライアント・required action・アカウント | `--import-realm` は既存レルムがあると再インポートしないため、残すと `infra/keycloak/realm-export.json` と実環境の一致を誰も検証していない状態になる |
| `lets_blog_server_wordpress_sites` | `/var/www/html/sites/*` のサイト実体 | プロビジョニングのシナリオが「既にあるものを確認するだけ」に退化しないため |
| `lets_blog_server_rabbitmq_data` | キューとメッセージ | 前回実行のメッセージが残ると非同期シナリオの結果が変わる |
| `lets_blog_server_generated_images` | 生成画像の保存領域 | テストが作った成果物 |
| `lets_blog_server_bulk_upload_files` | 一括アップロードの一時ファイル | テストが作った成果物 |
| `lets_blog_server_avatar_images` | identity-service のアバター画像(`AVATAR_STORAGE_PATH: /app/data/avatars`) | テストが作った成果物 |
| `lets_blog_server_comfyui_output` | ComfyUI の output | テストが作った成果物 |
| `lets_blog_server_penpot_postgres` | Penpot の DB | 既定は破棄。再構築コストが問題になれば保全へ移す(#965 Open Questions) |
| `lets_blog_server_penpot_assets` | Penpot のアセット | 同上 |

| 保全するボリューム | 保全する理由 |
| --- | --- |
| `lets_blog_server_comfyui_models` | 画像生成の**モデル重み**。再取得に長時間かかり、そもそもテスト対象の状態ではない。`CreatedAt` が実行をまたいで変わらないことをスクリプトが検証する |
| `lets_blog_server_ollama_models` | Ollama の**モデル重み**(約4.7GB)。再取得に長時間かかり、受け入れテストの LLM 呼び出しは `llm-stub` が受けるためテスト対象の状態でもない。`comfyui_models` と同様、`CreatedAt` が実行をまたいで変わらないことをスクリプトが検証する |

`*_test` スキーマ(ホストからの `./gradlew test` 用)は `mysql_data` ごと巻き添えで消えるが、
構築時に `infra/mysql/init/02-create-test-schemas.sh` が本来の経路で作り直す。
作り直されたことはスクリプトが確認する(確認できなければ非0で終了する)。

ホスト側に残る前回実行の生成物も撤去と同時に消す。

| 対象 | 誰が消すか |
| --- | --- |
| `apps/web/test-results/` / `apps/web/playwright-report/` | `rebuild-acceptance-env.sh`(撤去の直前) |
| `apps/web/.features-gen/` | `npm run test:at:clean` が `bddgen` の直前に消す。**globalSetup から消してはいけない** — Playwright は globalSetup の**あとに**テストファイルを読み込むため、実行中のテストが消える |

> **副次的な効果**: 空のボリュームから Flyway に再作成させるので、
> 「マイグレーションが空スキーマから通るか」も毎回検証される(#914 の契約テストと同じ性質)。

### 撤去の粒度が違う3本を残してある(統合しない)

| スクリプト | 何をするか | いつ使うか |
| --- | --- | --- |
| `scripts/rebuild-acceptance-env.sh` | 撤去 → ボリューム破棄(9本)→ ソースからビルドして起動 → 検証 | `test:at:clean`。ゼロ構築を検証したいとき |
| `scripts/reset-acceptance-env.sh` | 既存コンテナへの `docker exec` でデータ層だけを初期化 | 速く戻したいとき。コンテナ・イメージ・ボリュームは残る |
| `scripts/verify-clean-volume-boot.sh` | `docker compose down` → **`mysql_data` だけ**を `docker volume rm` → ビルドせずに起動 → healthy 待ち | 空の MySQL ボリュームから起動できるかだけを狭く問いたいとき(#668) |

#### `verify-clean-volume-boot.sh`(#668)と統合しない理由

`verify-clean-volume-boot.sh` は `docker compose down`(`-v` は付けない)のあと
`com.docker.compose.volume=mysql_data` ラベルの付いたボリューム**1本だけ**を消し、
`docker compose up -d gateway` で(**イメージをビルドせずに**)起動して、
`scripts/wait-for-stack-healthy.sh` で全コンテナが healthy になるのを待つ。
検証はそこまでで、レルムやスキーマやプローブは見ない。

破棄する範囲だけを見れば `rebuild-acceptance-env.sh` はこの上位互換である
(`mysql_data` を含む9本を破棄し、さらにソースから `--build` する)。
それでも**統合せず、両方を残す**。理由は3つある。

1. **問うている質問が違う。** `verify-clean-volume-boot.sh` は既存のイメージと既存の
   Keycloak 状態を保ったまま、**MySQL の初期化と Flyway だけを変数にする**。
   ゼロ構築が落ちたときは、イメージのビルド・レルムの再インポート・9本のボリュームの
   再作成が同時に変わっているので、原因の切り分けにならない。#668 の起動デッドロック
   (#583 / #786 / #785 で解消済み。[DOCKER_COMPOSE_ARCHITECTURE.md](DOCKER_COMPOSE_ARCHITECTURE.md))の
   回帰を狭く・速く問う道具として意味が残る。
2. **統合すると安全装置(1)を自分で壊す。** 1本にまとめるには
   `rebuild-acceptance-env.sh` に `--target` と「`mysql_data` だけ破棄する」を足すことになるが、
   破棄範囲と起動対象を引数で選べるようにした時点で、上の
   「接続先・プロジェクト・ボリュームを引数で差し替えられない」という保証は消える。
   統合の対価としては高すぎる。
3. **`verify-clean-volume-boot.sh` は受け入れテスト環境を準備できない。** 素の
   `docker compose`(`docker-compose.yml` だけ)で起動し、`docker-compose.e2e-stubs.yml` も
   `docker-compose.shared-host.yml` も重ねない。ゼロ構築の経路(`test:at:clean` →
   `globalSetup`)が呼ぶのは `rebuild-acceptance-env.sh` **だけ**であり、
   `verify-clean-volume-boot.sh` は受け入れテストの経路から呼ばれない手動の診断用スクリプトである
   (参照元は [DOCKER_COMPOSE_ARCHITECTURE.md](DOCKER_COMPOSE_ARCHITECTURE.md) と
   [e2e-validation-guide.md](e2e-validation-guide.md) §2.4)。

したがって #965 では `verify-clean-volume-boot.sh` を削除も変更もしない。
将来 #668 の回帰を狭く問う価値が無くなったと判断するなら、削除は別Issueで扱う。

### 安全装置

**(1) 接続先を引数で差し替えられない — 維持する。**

両スクリプトとも**接続先を指定するオプションを持たない**。コンテナ名(`lbs-mysql` /
`lbs-keycloak` / `lbs-wordpress` ほか)、レルム名(`letsblog`)に加えて、ゼロ構築側は
**compose プロジェクト名(`lets_blog_server`)とボリューム名も**スクリプト内で `readonly` に
固定してある。`scripts/provision-e2e-keycloak-users.sh` と同じ設計で、共有/本番環境では
実行できない。破壊対象は `docker-compose.yml` と `docker-compose.e2e-stubs.yml` の
compose プロジェクトに閉じる。

`--yes` なしはドライランで、**破棄するボリューム / 保全するボリューム / これから作る
プローブ**の3つを表示し、何も変更しない(プローブも作らない)。

```bash
./scripts/rebuild-acceptance-env.sh          # ドライラン。何も変更しない
./scripts/rebuild-acceptance-env.sh --yes    # 実行
./scripts/rebuild-acceptance-env.sh --yes --no-cache   # イメージをキャッシュ無しで作り直す
```

**(2) 削除対象のドメイン限定 — ゼロ構築経路には適用できないので、前提の明文化に置き換える。**

データ層リセット経路(`reset-acceptance-env.sh`)は、Keycloak のユーザー削除を
`@letsblog.local` ドメインに限定している。**この限定はそのまま維持する**(低リスクな経路
として残す意味があるため)。

しかしゼロ構築経路には、この安全装置を**原理的に適用できない**。`keycloak_postgres` ごと
破棄するので、どのアカウントを残すかを選ぶ余地が無いからである。代わりに前提を明文化する。

> **受け入れテスト環境の `letsblog` レルムには、失って困るアカウントを置かない。**
> テストが使うアカウントはすべてテスト自身がその実行の中で作る。恒久的に保持したい
> 実アカウントが必要になったら、受け入れテスト環境ではない別環境で扱う。

これは利用者の判断(2026-09-04)であり、実測でも裏づけられている。

| 確認(2026-09-04、`develop` / スタック稼働中) | 結果 |
| --- | --- |
| `kcadm get users -r letsblog --fields username,email` | `[ ]`(エンドユーザー0件) |
| `SELECT COUNT(*) FROM lbs_identity.users` | `0` |
| `infra/keycloak/realm-export.json` の `users` | `service-account-letsblog-services` のみ |

テストが使うアカウントは既に全てテスト自身が作っている。

| 段階 | 作るアカウント | 実体 |
| --- | --- | --- |
| `at-setup` | 最初の管理者(`E2E_PROVISION_ADMIN_EMAIL`) | `apps/web/e2e/features/auth/setup.feature` のシナリオが `/setup` 画面から作成する |
| `at-seed` | `e2e-test@letsblog.local` / `e2e-admin@letsblog.local` | `scripts/provision-e2e-keycloak-users.sh`(`e2e-*@letsblog.local` 以外は明示的に拒否する) |

**`E2E_PROVISION_ADMIN_EMAIL` には合成ダミーのアドレス(`@letsblog.local`)を指定すること。**
ゼロ構築のたびに作り直されるアカウントであり、個人の実アドレスを置く場所ではない。
値そのものはリポジトリ外の `~/.config/lets-blog-e2e.env` にある。

ゼロ構築スクリプトが**自分で作る**アカウントは `at-wipe-probe-<epoch>@letsblog.local` だけで、
それ以外のアカウントを名指しで作成・削除する処理は持たない。

### ウォッシュアウト・プローブ — 撤去が成立したことを証明する

「消えていること」だけを見る検証には弱点がある。**そもそも何も入っていなかった場合と
区別できない。** そこで撤去の直前に自分でダミーを3つ置き、構築後にそれが消えていることで
破棄の成立を示す。

| いつ | 何を | どこへ | 乗るボリューム |
| --- | --- | --- | --- |
| 撤去の直前 | Keycloak ユーザー `at-wipe-probe-<epoch>@letsblog.local` | `kcadm create users -r letsblog` で**直接**作る | `keycloak_postgres` |
| 撤去の直前 | MySQL データベース `at_wipe_probe_<epoch>` | `docker exec lbs-mysql mysql` | `mysql_data` |
| 撤去の直前 | ディレクトリ `/var/www/html/sites/at-wipe-probe-<epoch>` | `docker exec lbs-wordpress` | `wordpress_sites` |

- Keycloak は identity-service の API ではなく **`kcadm` で直接作る**。ゼロ構築の直前は
  最初の管理者が居るとは限らず、API 経由では作れないことがあるため。
- MySQL は9つのサービススキーマを汚さないよう**独立したデータベース**として作る。
- **作成直後に3つとも存在することを確認する。** ここを省くと、後の「消えている」が
  「そもそも作れていなかった」と区別できない。
- **個別に削除する処理は書かない。** 消すのはボリューム破棄そのものであり、
  それで消えること自体が検証対象である。
- 構築後に3つとも存在しないことを確認し、**1つでも残っていればどれが残ったかを名指しして
  非0で終了する**(残るということは、そのボリュームが破棄されていない)。
- 直前のスタックが起動していない場合(初回実行など)はプローブを省略し、
  `プローブ省略(直前のスタックが起動していないため)` と出力して、
  ボリュームの `CreatedAt` 検査だけで判定する。省略したことは必ず出力に残る。
- ドライランではプローブを作らない(作成予定として表示するだけ)。

プローブは**スクリプトが直前に自分で作ったものだけ**であり、`at-wipe-probe-` 接頭辞で
一意に識別できる。既存のアカウント・DB・サイトには触れない。

### ゼロ構築は自分で検証する

スクリプトは、最後に**成立したことを確かめてから**終わる。
「消したつもり」「作り直したつもり」で終わらせない — 不完全なまま受け入れテストを始めると、
前のデータに依存した結果が出て、しかもそれが分からない。

1. 破棄対象のボリュームが**新規に作成されたものである**
   (`docker volume inspect --format '{{.CreatedAt}}'` が実行開始時刻より後)
2. `comfyui_models` の `CreatedAt` が**変わっていない**(保全が成立している)
3. 全サービスが healthy(`scripts/wait-for-stack-healthy.sh` を再利用。重複実装しない)
4. 9スキーマに Flyway 管理テーブル以外のデータが無い
   (`roles` / `role_permissions` はマイグレーションが投入するマスタデータなので除外)
5. `*_test` スキーマが `02-create-test-schemas.sh` で作り直されている
6. `letsblog` レルムが存在し、ユーザーが `service-account-letsblog-services` のみ
7. WordPress にサイト実体が無い
8. `GET /api/auth/setup-status` が gateway 経由で 200 / `needsSetup: true` を返す
9. ウォッシュアウト・プローブが3種とも消えている

> 8番目は #951 の名残である。gateway の DNS キャッシュが古く、同時再起動で IP が
> 入れ替わると**別のサービスへ転送し続ける**という不具合があった(修正済み)。
> 宛先の取り違えは症状が 401 なので認可の設定を疑ってしまう。全コンテナを作り直す
> ゼロ構築ではこの検出はより重要になる。

いずれかが崩れていればスクリプトは非0で終了し、**後続の段階は実行されない**。

### GPU を持たないホストと、80/443 を共有するホスト

ゼロ構築は開発機の実環境を作り直すので、その環境の癖を2つ吸収する。

- **`comfyui` が起動できないホスト。** 以前はサービス無指定の `docker compose up -d` が
  1つのサービスの起動に失敗した時点で**中断**していた。NVIDIA ランタイムが無いと
  `comfyui` は `could not select device driver "nvidia"` で落ち、依存関係の下流
  (web / gateway / keycloak / 各ドメインサービス)が `created` のまま残っていた。issue #1066
  で `comfyui` に `profiles: ["gpu"]` を付けたため、GPUの無いホストでは `docker compose
  up -d` がそもそも `comfyui` のコンテナを作らなくなり、この中断は起きなくなった。
  このスクリプトが必須サービスと任意サービス(`comfyui`)を**分けて**起動し、任意サービスの
  起動失敗を警告に留める作りは#1066より前からの防御として残している
  (`docker compose up -d comfyui` は `--profile gpu` を指定しなくても明示的にサービス名を
  指定すれば起動を試みるため、GPUの無いホストではやはり失敗しうる。起動できなくても
  ボリュームは作られるので、破棄検証は成立する)。
- **ホストの 80/443 を他プロセスが握っているホスト(§12)。** `lbs-reverse-proxy` が
  ポートを公開していないことでこの構成を判定し、`docker-compose.shared-host.yml` を重ねて
  起動し、構築後に `scripts/setup-shared-host-proxy.sh` を再適用する
  (ネットワークを作り直すと `infra-proxy` の接続が失われるため)。再適用では
  `LBS_BASE_URL=` で到達確認を省く。`up -d` が返った直後は web / gateway がまだ起動途中で
  `https://localhost/` は 502 を返し、そこで落とすと「まだ早いだけ」でゼロ構築が止まるため。
  到達性の判定はリトライを持つ `wait-for-stack-healthy.sh` に委ねる。

### 所要時間(2026-09-04 実測)

| 対象 | 実測 | 内訳 |
| --- | --- | --- |
| ゼロ構築(**ビルドキャッシュあり**。既定) | 252 秒 / 260 秒(2回計測。約 4 分) | うちビルドと起動 169 秒 / 167 秒 |
| ゼロ構築(**ビルドキャッシュなし**。`--no-cache`) | 804 秒(約 13 分) | うちビルドと起動 710 秒 |
| データ層リセット(`reset-acceptance-env.sh`。参考・2026-09-01 実測) | 約 30 秒 | — |
| `at-seed`(2026-09-01 実測) | 約 8 秒 | — |

ゼロ構築はデータ層リセット(約30秒)の**8倍以上**かかる。支配項はイメージのビルドと起動で、
残り(80〜90秒)が空のボリュームからの初期化と healthy 待ち — MySQL の初期化、
9サービス分の Flyway、Keycloak のレルムインポートである。

**`--no-cache` を既定にしない。** 実測で所要時間が約3倍(4分 → 13分)になる一方、
`--build`(既定)でも**変更されたレイヤは作り直される**ので、ソースの変更が反映されるか
どうかの検出力は変わらない。`--no-cache` が要るのは、ベースイメージや依存取得の側を
疑うときだけである。

**ゼロ構築を省略する選択肢は取らない**
(それをやると「前のテストの残骸に依存して通るテスト」が戻ってくる)。
速く回したいときは `npm run test:at:fast`(既存スタックに対する高速経路)を使うこと。

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

**後片付けをしない理由は「次回実行の先頭で全撤去するから」である**(利用者の方針そのもの。
本節冒頭を参照)。終了時に消さないので、失敗の調査は実行後のスタックに対してそのまま行える。

`global-teardown.ts` と `E2E_DB_CLEANUP` を残しているのは、`.feature` へ未移行の
Playwright spec(`apps/web/e2e/*.spec.ts`)が「既存データを壊さない一意なフィクスチャ」という
**逆の前提**で書かれており、その孤児行の掃除には依然として必要だから(#765)。
全 spec の移行が終わった時点で teardown ごと削除する(§7)。

---

## 11. JUnit のテストと重複したら、どちらを正とするか

横断的な性質(認可・ルーティング)は、JUnit のコントラクトテストでも守られている。
同じことを2か所で検証すると、片方だけが直された状態に必ずなる。そこで**役割を分ける**
(issue #943 / AT-17 の Implementation Notes)。

| 検証したいこと | 正とする場所 | 理由 |
| --- | --- | --- |
| ルート表の転送先が**正しいサービス**か / 転送先にハンドラがあるか | `services/gateway/src/test/java/com/letsblog/gateway/config/RouteControllerContractTest.java` | 静的に全ルートを網羅でき、スタックの起動も要らない。実行時に「どのサービスが応答したか」を外から見分ける手段は無い |
| 公開エンドポイントが**利用者から到達できる**か(経路なし404にならないか) | `apps/web/e2e/features/cross-cutting/gateway-routing.feature` | 利用者から見たふるまい。ルート表が正しくてもサービスが落ちていれば到達しない |
| 「Authorizationヘッダーが無ければ401」 | 各サービスの `AuthorizationMatrixIntegrationTest` | サービス単体の契約。gateway もスタックも介さず速い |
| 認可表の全行が**利用者から見て**拒否されるか / 表に載っていない公開エンドポイントが無いか | `apps/web/e2e/features/cross-cutting/authorization-matrix.feature` | 表と実装の乖離は、サービス単体のテストからは見えない(#731) |

### 一斉走査が触らないもの

`gateway-routing.feature` と `authorization-matrix.feature` は全エンドポイントを1回ずつ叩く。
次の2種類だけは対象から外している(`apps/web/e2e/support/endpoints.ts`)。

- **gateway に経路が無いもの**(`/api/render/**`、`/api/comfyui/checkpoints/*`)。
  コンテナ間で直接呼ばれる経路しか無く、公開エンドポイントではない。
  `RouteControllerContractTest` の `NON_GATEWAY_ROUTED_PATHS` と同じ集合
- **gateway の `upload-endpoint` バケットに入るもの**(実アップロード・実生成の4本)。
  `POST /api/media/upload`、`POST /api/ai/image`、
  `POST /api/projects/{id}/asset-images/{generatedImageId}/upload`、
  `POST /api/projects/{id}/bulk-management/upload` の4本。
  このバケットは**プロセス全体で1時間に10回**しかない。この4本のために枠を使い切ると、
  同じ1時間に走る画像アップロード系のシナリオが巻き添えで429になる。
  この4本の経路は `RouteControllerContractTest` が静的に担保する

  issue #999 より前は「パスに `/upload` か `/image` を**含む**」という部分一致で判定して
  いたため、`GET /api/projects/{id}/image-settings` のような軽量な設定APIまで巻き込み、
  除外対象が8本あった。今は実アップロード・実生成だけを列挙する**許可リスト**方式で、
  gateway 側(`RateLimitWebFilter`)と同じ定義を `isUploadBucketPath` が持つ。
  両者の一致は `RateLimitUploadBucketSyncTest` が検証する
  (`docs/API_RATE_LIMITING.md`)

---

## 12. ホストの80/443を他プロセスが占有している場合(#1038)

受け入れテストの baseURL は `apps/web/playwright.config.ts` で **`https://localhost` に
ハードコード**されている。Keycloak の `redirect_uri` が
`https://localhost/api/auth/callback/keycloak` として固定登録されており、実行時に
差し替えられないためである。この URL を受けるのは `docker-compose.yml` の
`reverse-proxy` で、**ホストの 80/443 を占有できること**を前提にしている。

その前提が成り立たないホスト(この開発機では GitLab を提供する `infra-proxy` が
先に `0.0.0.0:80` / `0.0.0.0:443` を握っている)では、受け入れテストが**1本も実行できない**。

### 症状

```bash
# 到達できない。2xx か 3xx が返らなければ、この節が該当する
curl -sk -o /dev/null -w '%{http_code}' https://localhost/
```

`000`(接続断)や `502` が返る。`npm run test:at:fast` は
`apps/web/e2e/global-setup.ts` の疎通確認で落ち、`@stage:` の全プロジェクト
(`at-setup` / `at-seed` / `at-provision` / `at-main` / `at-destructive`)が始まらない。

### 症状の見えにくさ — コンテナは healthy を返し続ける

ポート競合の帰結は「ポートが公開されないこと」だけでは済まない。**Docker はネットワーク
接続時にポート公開を行うため、公開に失敗したコンテナはどのネットワークにも所属しないまま
running になる。**

```bash
docker inspect lbs-reverse-proxy --format '{{json .NetworkSettings.Networks}}'   # {}
docker exec lbs-reverse-proxy wget -q -O /dev/null -T 3 http://web:3000/         # bad address
```

`web` にも `gateway` にも到達できないので、仮にポートが公開されていても何も中継できない。
それでも旧来のヘルスチェックは自分の netns 内の `127.0.0.1:80/nginx-health` を叩くだけ
だったので `healthy` を返し、`scripts/wait-for-stack-healthy.sh` は
`OK: 対象サービスは全てhealthyです` を返していた。**壊れていることがスタックの健全性
チェックから見えない**のが、この問題の最も厄介なところだった。

現在はどちらも是正してある。

- `docker-compose.yml` の `reverse-proxy` ヘルスチェックは上流到達性
  (`nc -z web 3000 || nc -z gateway 8080`)を含む。孤立時は名前解決に失敗して unhealthy になる
- `scripts/wait-for-stack-healthy.sh` は、コンテナが全て healthy になったあとに
  **ホストから baseURL へ届くこと**も確認する。切り分けだけしたいときは
  `--http-only`、意図して省くときは `--skip-http-check`(または `E2E_SKIP_HTTP_CHECK=1`)

### 対処 — 共有プロキシに vhost を足して共存させる

`localhost` も `server.tonoccho.local` も `/etc/hosts` でどちらも `127.0.0.1` に解決される。
したがって **IP で分離することはできない**。分離できる軸は Host ヘッダ / SNI だけであり、
それはまさに nginx の vhost が担う役割である。そこで、占有している側のプロキシへ
`server_name localhost;` の vhost を足し、`lbs-reverse-proxy` へ中継させる。

配置する設定は本リポジトリで管理している([infra/shared-host/20-localhost.conf](../infra/shared-host/20-localhost.conf))。
適用・点検は次のスクリプトが行う。

```bash
# 適用済みかを点検する(何も書き換えない)
bash scripts/setup-shared-host-proxy.sh --check

# 適用する(冪等。何度実行してもよい)
bash scripts/setup-shared-host-proxy.sh

# 以後、スタックはポート公開なしで起動する(80/443 は占有側のもの)
docker compose -f docker-compose.yml -f docker-compose.shared-host.yml up -d

# 確認
curl -sk -o /dev/null -w '%{http_code}' https://localhost/   # 2xx か 3xx
cd apps/web && npm run test:at:fast
```

`docker-compose.shared-host.yml` は `reverse-proxy` の `ports` を `!override []` で
打ち消すだけのオーバーライドである(`ports: []` では compose がリストをマージするため
公開が残る)。**コミット済みの `docker-compose.yml` の既定は変えていない** —
ホストを単独で占有できる環境ではこれまでどおり何も足さずに起動できる。

配置先(既定 `/home/seiji/src/infra`)は `INFRA_DIR` で、コンテナ名は `PROXY_CONTAINER` で
上書きできる。

### 触るときの注意

**書き込み先は GitLab を提供している nginx の設定ディレクトリである。**壊すと GitLab が
止まり、`glab` に依存する開発ワークフローごと進行不能になる。スクリプトは

1. 適用前後で GitLab の生存を確認し、
2. `docker exec infra-proxy nginx -t` を通してからでなければ reload せず、
3. `nginx -t` が落ちたら **reload せずに配置したファイルを撤去して**異常終了する

ようにしてある。手で置き換えないこと。

**infra 側スタックを作り直したら、もう一度適用すること。**`docker network connect` は
コンテナに対する操作であって compose の定義ではないため、`docker compose up -d` などで
`infra-proxy` が作り直されると接続が失われる(設定ファイル自体は残る)。
`--check` が「接続されていません」と報告したらこれである。再実行すれば直る(冪等)。

適用しても安全であることは、infra の実設定(`00-common.conf` /
`10-server.tonoccho.local.conf`)と実際の証明書を並べた状態で `nginx -t` を通して確認できる。
上流を変数経由にしてあるため、**`lbs-reverse-proxy` を名前解決できないネットワーク上でも
検証は成功する** — これが「lbs スタックを止めていても GitLab が落ちない」ことの実証である。
直書きに戻すと同じ条件で `host not found in upstream "lbs-reverse-proxy"` になり、nginx は
起動できない。

vhost の中で最も重要なのは、上流名を**変数経由で遅延解決している**ことである。

```nginx
resolver 127.0.0.11 valid=10s ipv6=off;
set $lbs_upstream https://lbs-reverse-proxy:443;
proxy_pass $lbs_upstream;
```

`proxy_pass https://lbs-reverse-proxy:443;` と直接書くと nginx は**起動時**に名前解決する。
lets_blog_server スタックを停止している間に `infra-proxy` を再起動・reload すると
`host not found in upstream` で nginx 自体が起動できず、**GitLab ごと落ちる**。しかも
lbs スタックが動いている間は何の症状も出ないため、レビューでは気づけない。
`scripts/test_shared_host_proxy.py` がこの回帰を検査している。

### 採らなかった案

**案B: baseURL を環境変数化し、Keycloak に別の redirect_uri を登録する。**
ハードコードの理由そのものを解消できるが、影響範囲が広すぎる。公開 URL を変えるには
`docker-compose.yml` の `NEXTAUTH_URL` / `APP_WEB_BASE_URL` / `PMA_ABSOLUTE_URI` /
`KC_HOSTNAME`、`infra/keycloak/realm-export.json` の `redirect_uri`、
`apps/web/playwright.config.ts` の baseURL を全て揃える必要がある。とりわけ `KC_HOSTNAME`
は **Keycloak が発行するトークンの `iss` を変える**。つまりテスト環境の都合で、開発スタックが
名乗る公開 URL と発行済みトークンの意味そのものを変えることになる。テストのために製品側の
同一性を動かすのは筋が悪い。

**案C: 受け入れテストの実行中だけ infra-proxy を停止する。**
最も単純だが、実行中は GitLab が止まるため `glab` が使えなくなる。このプロジェクトの
開発ワークフロー自体が Issue の記録と Merge Request の操作に `glab` を使っているので、
受け入れテストを回している間はワークフローが一切進行できない。受け入れテストは
数十分単位で走るものであり、その間ワークフローを止める運用は現実的でない。

---

## 13. 実行区間の排他(#1187)

受け入れテストはMySQL・Keycloakの合成アカウント・`infra/e2e-stubs/**`のエラー注入状態・
WordPressのプロビジョニング結果といったホスト状態を共有し、`at-setup → at-seed →
at-provision → at-main → at-destructive` の段階順に走る。2つの実行が同一ホストで重なると、
一方が仕込んだ状態を他方が横取りし、**無関係なシナリオが確率的に落ちる**。

そこで、受け入れテストの実行開始時にホスト単位の排他ロックを獲得し、終了時に解放する
(`apps/web/e2e/at-lock.ts`)。2つ目の実行は1つ目の完了を待ってから始まる — スキップにも
失敗にもならない。

### なぜ npm script をラップしないのか

`npm run test:at` を `flock` で包む案は、`npx playwright test --project=at-main` のような
**直接起動**を取りこぼす(エージェントは実際によく直に叩く)。そこで、Playwrightのどの
起動経路でも必ず呼ばれる `globalSetup` / `globalTeardown`(`playwright.config.ts` のconfig
レベルの設定)でロックの獲得・解放を行う。`ACCEPTANCE_RESET=1` のゼロ構築(最大90分)も
ロックの獲得**後**に実行するため、撤去中に他方がテストするような重なりも防がれる。

ロックの獲得は、ブラウザ起動確認(`checkBrowsersLaunchable`、#1045)の**後**に置く。
ブラウザが無いホストを、ロック待ちで無駄に待たせないためである。

### OSが自動解放する仕組み

ロックファイルは `${XDG_RUNTIME_DIR:-<OSの一時ディレクトリ>}/lets-blog-server-acceptance-test.lock`
に置く(リポジトリ外。read-onlyステージのガードと衝突させず、worktree間で共有するため)。

Node自身がこのファイルのfdを開いたまま保持し、`flock` コマンドへそのfdを子プロセスの
`stdio` 経由で継承させて `flock(2)` を呼ばせる。`flock(2)` のロックはfd番号ではなく
「オープンファイル記述」に対して張られるため、子プロセスが直後に終了してもロックは
保持され続ける一方、**保持しているNodeプロセスが `SIGKILL` を含む何らかの理由で終了すれば、
カーネルが終了時に全fdを強制的に閉じるため、ロックは後始末コードなしに即座に解放される**。
次の実行はロック待ちにならず、直ちに獲得できる。

### 待機の可視化と上限

待機中は無言で止まらない。ロックの保持者(PID・獲得時刻)と自分の経過待機時間を、
一定間隔で標準出力へ出す。

環境変数:

| 変数 | 既定 | 意味 |
| --- | --- | --- |
| `AT_LOCK_FILE` | `${XDG_RUNTIME_DIR:-<OSの一時ディレクトリ>}/lets-blog-server-acceptance-test.lock` | ロックファイルの場所を上書きする |
| `AT_LOCK_TIMEOUT_SECONDS` | `7200`(2時間) | 待機上限。`ACCEPTANCE_RESET` のゼロ構築(最大90分)+テスト実行時間を見込む |
| `AT_LOCK_POLL_SECONDS` | `30` | 待機中の再試行間隔 |

待機上限を超えると、保持者の情報と `AT_LOCK_TIMEOUT_SECONDS` での調整方法を示した
明確なエラーメッセージとともに非0で終了する。

---

## 14. 共有スタックとの作業ツリー不一致検出(#1202)

無人ループを2〜3並列にすると、各ワーカーは別々のgit worktree・別々のブランチで動きながら、
§13の排他ロックで守られた**1本の共有dockerスタック**をテストする(2本目のスタックはホストの
メモリで成立しない)。ロックは「同時に2つが触らない」ことは保証するが、「今テストしようと
しているブランチのコードが、そのスタックに載っている」ことは何も保証しない。

### 何を検出するか

1. **プロダクションコードを変更したブランチのATが、変更前のコードを検証している状態。**
   `web` は `docker-compose.yml` の bind mount(`./apps/web:/app`)で**compose プロジェクト
   ディレクトリの** `apps/web` をそのまま見せるため、別worktreeが起動したスタックには
   自分のブランチの変更が乗らない。各JVMサービスは `build:` で作られたイメージなので、
   再ビルドしない限りブランチを切り替えても変わらない。どちらの場合も受け入れテストは
   **緑になり**、変更が効いていないことは誰にも見えない。
2. **`scripts/rebuild-acceptance-env.sh` を別worktreeから実行し、共有スタックを黙って
   乗っ取る状態。** 同スクリプトはcomposeプロジェクト名を `lets_blog_server` に固定した
   まま `up -d --build` するため、実行した作業ツリーのブランチへ bind mount とイメージが
   置き換わる。もう一方のワーカーは自分の知らないブランチに対してテストしていることに
   気づけない。

どちらも症状が「何も起きない」ため、`scripts/check-worktree-match.py` が両方の入口で
呼ばれる。

### 判定方法

「共有スタックをどの作業ツリーが作ったか」の権威は `docker compose ls --format json` の
`ConfigFiles`(絶対パス、カンマ区切り)。「今の作業ツリー」は `git rev-parse
--show-toplevel`。両者が一致しなければ不一致とみなす。

プロダクションコードかどうかの判定は `.claude/hooks/paths.py` の `classify()` を使う
(`CLAUDE.md` → **Test-First Implementation** が定める唯一の分類器。ここに規則を
書き写さない)。差分の起点は `git merge-base origin/develop HEAD` — ローカルの `develop`
ブランチが最新かどうかに依存しない。

| 呼び出し元 | 何を確認するか | 不一致のときの挙動 |
| --- | --- | --- |
| `apps/web/e2e/global-setup.ts`(`check-worktree-match.py at-start`) | 作業ツリー不一致 **かつ** `origin/develop` からのプロダクションコード変更あり | シナリオを1本も実行せず、非0で終了。`git diff` で変更されたプロダクションコードのパスと、両方の作業ツリーのパスをメッセージに含める |
| `scripts/rebuild-acceptance-env.sh` | 作業ツリー不一致(プロダクション差分は問わない) | 撤去(手順1/5)を始める前に非0で終了して警告する |

`.feature` とステップ定義だけを変更したブランチは、`classify()` がテストコードと判定する
ため、作業ツリーが不一致でも受け入れテストは通常どおり開始される — 変更が反映されない
共有スタックに対して意味のある検証は、そもそもプロダクションコードの側にしかない。

`rebuild-acceptance-env.sh` の側がプロダクション差分を問わないのは、「再構築」という操作
そのものが相手の足元のスタックを作り替えてしまうためで、そこに自分のブランチが
プロダクションコードを変更しているかどうかは関係が無い。

### 迂回

環境変数 `AT_WORKTREE_CHECK_BYPASS=1` が、このチェック全体を明示的に迂回する唯一の
エスケープハッチである。迂回した場合は必ず標準出力にその旨が記録される。単一worktree
運用でこのチェックが常に前提外の判定をしてしまう場合や、意図的に別worktreeが作った
スタックへ向けて検証したい場合に使う。

### 直し方

- **AT側で拒否された場合**: 表示された「スタックを作った作業ツリー」から
  `scripts/rebuild-acceptance-env.sh --yes` を実行するか、自分の作業ツリーから
  同スクリプトを実行して共有スタックを自分のブランチへ作り替える。あるいは、
  スタックを作った作業ツリー自身から受け入れテストを実行する。
- **`rebuild-acceptance-env.sh` 側で拒否された場合**: 他ワーカーが今その共有スタックを
  使っている可能性が高い。相手の完了を待つか(§13のロックは別のプロセス起動ごとに
  独立して働くため、これ自体はロック待ちにならないことに注意)、本当に自分が
  作り替えてよいと確信できる場合のみ `AT_WORKTREE_CHECK_BYPASS=1` を付けて実行する。

### スコープ外

- 食い違いを自動で解消すること(共有スタックを勝手に再ビルドしない)。検出して止める
  ところまでが本チェックの役割である。
- 複数スタックのインスタンス化。ホストのメモリで成立しない。
- 無人ループのランナー(このリポジトリ外)が、プロダクションを触るIssueを並列キューから
  あらかじめ除外すること。
- ホストで走る `./gradlew` テストの同種の検査。

---

## 15. develop の検証済みコミットに版数コミットを積み、main へ載せて semver タグを付け、
    develop を次の開発版数へ進める(#1274, #1305)

`scripts/release-verify-tag.py` は、develop のコミットを1つ指定して(既定・実質必須は
`origin/develop` の先頭。詳細は手順1)、次を1コマンドで行う。

```
python3 scripts/release-verify-tag.py [commit] [--bump patch|minor|major]
```

### 使い方と手順の一覧

1. P(`origin/develop` の先頭)を固定する。指定したコミットが現在の `origin/develop` の
   先頭と一致しなければ、どの手順も始めずに拒否する(#1305: 古いコミットを指定した
   リリースはできない)。
2. 版数 X を `compute_next_version` で決める(テスト手順より前。#1305 要件2)。同名タグが
   既に origin に存在しないかを、ここ(計算時)と push 直前の両方で確認する。
3. メイン作業ツリーとは別の**clone**(`git worktree` ではない。§「なぜ push か」参照)で
   検証する。メイン作業ツリーの HEAD・ブランチ・インデックス・ファイルは変更しない。
   まず P を detached で checkout し、**版数ファイルだけを変更するリリースコミット R**
   (P の子)を、git フックを束縛する**前**に作る(#1305 要件3。`apps/mcp-server/src/server.js`
   はプロダクション扱いなので、束縛したまま作ると隔離チェックアウトの pre-commit フックが
   テストファースト規則で拒否する)。以降の事前確認・全手順・本番の木チェックは、すべて
   **R に対して**行う(検証するのはタグの付く木そのもの)。gitignore 対象で clone に
   含まれない `.env` と `certs/` は、メイン作業ツリーから隔離チェックアウトへ別途コピーする
   (`.env` が無ければ、手順を1つも始めずに理由を表示して失敗する)。資格情報は
   `~/.config/lets-blog-e2e.env` から読み込み、各手順のサブプロセス環境へ渡す。
4. §13 の `cycle.lock`(`${CLAUDE_AUTO_STATE_DIR:-~/.local/state/claude-auto}/cycle.lock`、
   無人ループのスクリプトと共有)を、最初の手順を始める前から push と引き渡しの完了まで
   保持する。待機中は保持者と経過時間を表示し、上限(環境変数 `RELEASE_VERIFY_LOCK_TIMEOUT`
   で変更可。既定は **28800秒(8時間)** — パイプライン1回が最大で3件×160分
   (約8時間)に達しうるため(Q4、Issue Open Questions)、通常の1〜2時間の実行を
   打ち切らない側に余裕を持たせて8時間にしている)を超えたら origin に触れずに非0で
   終了する。
5. **事前確認**: `origin/main` の先頭に R を `--no-ff` でマージし、結果の木が R の木と
   同一であることを確かめる。不一致なら、テスト手順を1つも始めずに失敗する(誰かが main
   へ直接コミットした、などのずれを、1〜2時間かかる検証の前に検出するため)。
6. 依存導入(`npm ci`)、`scripts/` と `.claude/hooks/` の Python ユニットテスト、
   `apps/web` / `apps/extension` / `apps/mcp-server` / `apps/penpot-plugin` /
   `packages/api-client` のテスト・lint・build・typecheck、`apps/web` の
   `npm run test:at:clean`(`ACCEPTANCE_RESET=1`、迂回 `AT_WORKTREE_CHECK_BYPASS=1` を明示的に
   使い、ログに残す。迂回の根拠は手順4の `cycle.lock`)、`apps/extension` の
   `npm run test:at`、`docker-compose.host-tests.yml` を重ねて MySQL を公開し直したうえでの
   `scripts/check-test-db.sh` と `./gradlew test lint` を、R をチェックアウトした隔離
   チェックアウトで実行する。
7. **ゼロ許容**: 全手順の終了コードが0であり、かつ failed / skipped / did not run / flaky が
   すべて0であるときだけ成功とする。既知の失敗(#1140、#1262 など)も例外にしない。
   終了コードだけでは隠れる skip を防ぐため、`web-test`(jest `--json`)・
   `web-test-at-clean`(Playwright の `json` レポーター)・`extension-test-at`
   (jest `--json`)・`backend-gradle-test-lint`(全11モジュールの JUnit XML を集計)の
   4手順は、実際の出力ファイルから passed/failed/skipped/flaky を抽出して判定する。
8. 成功したら、`origin/main` を再取得してもう一度マージ+木の同一性確認を R に対して
   行い(検証中に main が進んでいてもそのときの先頭に対して行う)、同名タグの不存在と
   `origin/develop` が依然として P のままであることを再確認する(#1305: 検証中に develop が
   進んでいたら、再試行せず全体を失敗として扱う)。
9. **次の開発版数コミット V**(R の子。全箇所を `X.Y.(Z+1)-DEVELOP` へ。`DEVELOP` は大文字)
   を作る。マージコミット M(main, R)に P と R の SHA を記録した注釈付き semver タグ X を
   付け、`git push --atomic origin M:refs/heads/main V:refs/heads/develop refs/tags/X` で
   `main`・`develop`・タグを**1回で**送る(#1305 要件5: `--force` は使わない)。
10. 成功・失敗にかかわらず、終了前(`cycle.lock` を保持している間)に共有スタックを
    メイン作業ツリーが所有・稼働する状態へ戻す。

失敗した場合は、`origin` の `main` は実行前のまま、新しい semver タグは origin にも
ローカルにも残らず、失敗した手順の名前と理由・ログの場所が表示されて非0で終了する。
ログ・`docker compose logs`・Playwright のレポートはリポジトリの外
(`~/.local/state/release-verify-tag/`)に保存し、コミットしない。

### semver の上げ方

タグ名は `MAJOR.MINOR.PATCH`(`v` 接頭辞なし)。次の番号は、origin 上の厳密な semver
タグ(`^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$` に一致するもの。`dev-*` や
`v1.0.0`、プレリリース表記は無視する)の最大値を**数値として**比較し、`--bump` に従って
上げたものとする(`0.9.0` と `0.10.0` があれば `0.10.0` を基準にする。文字列順ではない)。

`--bump` の既定は `patch`。既存の `0.3.0` から数えて最初の手動リリースだけ、利用者が
`--bump minor` を明示して実行し `0.4.0` にする。以後は省略して `0.4.1`、`0.4.2` … と
進める。

### ゼロ許容

要件5のとおり、既知の失敗も含めて1件でも failed / skipped / did not run / flaky があれば
リリースを作らない。「ほぼ通った」を許容する経路は無い。

### main へのマージ・develop への次期開発版数コミットが Issue の MR ではなく直接 push である理由

`.claude/CLAUDE.md` の squash / `ff` の規則(**Where squash is enforced**)は develop へ
入る **Issue の Merge Request** のためのものであり、本スクリプトが main へ行う直接 push、
および R の検証成功後に develop へ行う V の直接 push は、その規則の対象ではない
(下の1文、および `.claude/CLAUDE.md` **Where squash is enforced** の該当箇所を参照)。
develop への push はこのスクリプトだけの例外で、Issue の MR / squash の規則には違反しない
(#1305 利用者決定)。MR では実現できない理由は次のとおり:

- プロジェクト設定の `merge_method: ff` は、ソースブランチが対象ブランチの先頭を含んで
  いることを要求する。develop は main にしか無い6コミットを含まないため、develop の
  rebase を求められる。develop は保護ブランチで強制 push できない。
- 仮に squash されると、develop の親を持たないコミットが1つ main に積まれ、以後の
  「衝突しない・木が一致する」という不変条件(次項)が崩れる。

木の同一性確認は、main の木が(検証済みの)develop の祖先の木と常に一致しているという
不変条件を守るための機械的な検査である。初回は、既存の `0.3.0` 以降 main にだけ積まれた
6コミットが merge-base の木を変えていない(`git diff` が空)ことに支えられている。
2回目以降は、前回のリリースで main の木が検証済み SHA の木と一致した状態になっている
ことに支えられる。崩れるのは、誰かが main へ直接コミットしたときだけで、それを検出する
のが事前確認・本番確認の役目である。

### ロックと引き渡し

`cycle.lock` は無人ループ(`~/.local/bin/claude-auto-*.sh`)と共有する。ロックを保持して
いる間だけ、この検証は共有スタック(と、それを使う無人ループ)を止める。実行後は
成功・失敗にかかわらず、ロックを解放する前に共有スタックをメイン作業ツリーの所有へ戻す
(未整理のままだと `docker compose ls` からは消えていても、次のループが行う
`--no-deps` 実行が health 待ちで落ちる)。

### GitHub への反映

このスクリプトは GitLab(origin)にしか触れない。GitHub の資格情報は使わず、ミラーの
完了も待たない。main とタグが GitHub に届くのは #1256 の push ミラーの役目である。

### 想定所要時間

未実測(実機での初回実行は #1278 の解消後になる見込み)。ゼロ構築だけで約4分(§10)。
全体では1〜2時間程度を想定する。実測が取れたらここへ追記する。

### ループが稼働中のとき

無人ループが `cycle.lock` を保持している間は、上記のとおり待機し、上限を超えれば
何も変えずに失敗する。逆に本スクリプトがロックを保持している間は、無人ループの次サイクル
は開始できない。

---

## 16. E2Eログイン経路の列挙(#1295)

共有E2E合成アカウント(`E2E_TEST_EMAIL`=`e2e-test@letsblog.local` /
`E2E_ADMIN_EMAIL`=`e2e-admin@letsblog.local`)のブルートフォース検知
(`user_temporarily_disabled`)は2つの独立した機構で起きる。

- **機構1(quick login)**: 複数ワーカーが同じアカウントへ短時間に認証を要求すると、
  `quickLoginCheckMilliSeconds`(1秒)以内の重なりで`minimumQuickLoginWaitSeconds`
  (60秒)のロックが掛かる。対策は**アカウント単位のロック**(`apps/web/e2e/account-lock.ts`
  の`withAccountLock`)を経由させること。
- **機構2(failureFactor)**: `failureFactor`(=5)は並列度と無関係な、実行を通じた
  失敗回数の累積。わざと失敗させるログインが共有アカウントへ失敗を積み重ねる。対策は
  **使い捨てアカウントへの隔離**(利用者の決定、issue #1295)。

### 列挙(再現可能なgrep、2026-09-16時点)

```
grep -rn "kc-login" apps/web/e2e/
grep -rn "grant_type: 'password'" apps/web/e2e/
```

各経路の分類(処置は下表の「注釈」列。`scripts/check-e2e-login-routes.sh`が新規の
未分類経路を機械的に検出する。§16.1参照):

| 経路 | シナリオ / ファイル | 分類 | 注釈 |
| --- | --- | --- | --- |
| `helpers.ts`の`loginViaKeycloak`(ブラウザUIログイン) | 全UIシナリオ共通 | ロック経由 | `e2e-login-guard:locked` |
| `token-cache.ts`の`fetchAccessToken`内部実装(パスワードグラント) | 全API直叩きシナリオ共通 | ロック経由 | `e2e-login-guard:locked` |
| `auth.steps.ts`「作成した管理者でログインすると管理者専用ページへ入れる」 | `setup.feature`、`@stage:setup @mode:serial` | ロック経由 | `e2e-login-guard:locked` |
| `auth.steps.ts`の`loginIfPromptedForDeviceCode` | `device-code.feature` | ロック経由 | `e2e-login-guard:locked` |
| `uiQuality.steps.ts`の`keyboardOnlyCreateProject` | `accessibility.feature` / `responsive.feature` | ロック経由 | `e2e-login-guard:locked` |
| `auth.steps.ts`「使い捨てアカウントのメールアドレスと誤ったパスワードを入力して送信する」 | `login.feature`「誤ったパスワードではログインできない」 | 使い捨てへ隔離 | `e2e-login-guard:disposable` |
| `auth.steps.ts`「無効化したユーザーは新しくトークンを取得できない」 | `token-lifecycle.feature`「無効化したユーザーの発行済みアクセストークンは拒否される」、`@destructive` | 使い捨てへ隔離 | `e2e-login-guard:disposable` |
| `bruteForceLockout.steps.ts`(#1056) | `brute-force-lockout.feature` | 既に使い捨て・処置不要 | `e2e-login-guard:already-disposable` |
| `userDeactivation.steps.ts`(#1158) | `user-deactivation.feature` | 既に使い捨て・処置不要 | `e2e-login-guard:already-disposable` |

デバイス認可の同意ボタン(`auth.steps.ts`、`page.locator('#kc-login, input[name="accept"]...')`)
は上の列挙に含めていない。これは`#username`/`#password`へ資格情報を入力して送信する
ログインフォームではなく、既にログイン済みの状態での同意操作であり、Keycloakへ新たに
認証リクエストを送るものではないため。

### 16.1 新規経路の見落としを防ぐ仕組み

過去2回の差し戻し(note 7363のQA、note 7391)はいずれも「対策範囲の外にある経路」を
見落としたことが原因だった。`scripts/check-e2e-login-routes.sh`が、`apps/web/e2e/`配下の
`#kc-login`クリック/フォーカスと`grant_type: 'password'`の各箇所について、直前5行以内に
`e2e-login-guard:(locked|disposable|already-disposable)`という注釈コメントが無ければ
検査を失敗させる。新しいログイン送信経路をこの3分類のいずれにも分類せずに追加すると、
この検査が失敗する。単体テストは`scripts/test_check_e2e_login_routes.py`。

```
bash scripts/check-e2e-login-routes.sh
```

### 16.2 AC1(既定並列度での連続実行、ロック0件)の検証記録(2026-09-16)

レビュー差し戻し(note 8148)を受け、`npm run test:at:clean`(既定の並列度、
`E2E_WORKERS`未設定)でゼロ構築から実行した。

```
source ~/.config/lets-blog-e2e.env
cd apps/web
systemd-run --user --unit=at1295-<epoch> --collect \
  --working-directory=.../apps/web \
  --setenv=PATH=... \
  bash -c 'source ~/.config/lets-blog-e2e.env && npm run test:at:clean'
```

`at-setup → at-seed → at-provision → at-main` は1回の`npm run test:at:clean`実行で
連続して通った(294 passed / 14 failed、26.4分)。**除外は一切適用していない**
(`AT_EXCLUDE_REQUIRES_GPU`未設定、`--grep-invert`未使用)。

14件の失敗はいずれも本経路(Keycloakのブルートフォース検知)と無関係な、既知の
既存不具合だった。`docker logs lbs-keycloak`に`user_temporarily_disabled`は0件
(共有アカウント・使い捨てアカウントいずれも)。失敗内訳と、それぞれ既存Issueで
追跡済みであることの確認:

| 失敗したシナリオ | 追跡Issue |
| --- | --- |
| `ai/model-selection.feature`・`ai/resilience.feature`(LLMスタブ) | #1188(Ready、並列実行時の衝突) |
| `analytics/credentials.feature`・`analytics/report-failures.feature` | #1281(Done)と同系統の既知の不安定さ |
| `auth/token-lifecycle.feature`の2シナリオ(UI要素の可視性タイムアウト。`invalid_grant`ではない) | 本Issueとは無関係な画面側のタイミング |
| `identity/project-members.feature` | 既存の`@slow`系不安定さ |
| `media/comfyui-checkpoints.feature`の2シナリオ | 既に`@requires-gpu`付き。本ホストは実機GPU無し(`docker ps`に`lbs-e2e-comfyui-stub`のみ) |
| `platform/vscode-extension.feature`の2シナリオ・`project/ssh-key-pairs.feature` | 本Issueと無関係なビルド/API検証 |
| `ui-quality/accessibility.feature`・`ui-quality/internationalization.feature`(タイムゾーン) | #1317(Done)と同系統の既知の不安定さ |

Playwrightの依存プロジェクト機構(`playwright.config.ts:41-46`)により、`at-main`に
1件でも失敗があると依存元の`at-destructive`は自動実行されない。前回サイクルの
差し戻し理由は、この後`at-destructive`を**環境を再シードしてから**別実行し、
共有アカウントの累積失敗カウント(機構2)をリセットしてしまっていた点にある。

今回はこれを避けるため、**再シードを一切行わずに**、直前の`at-main`実行が残した
ライブな環境状態に対して`npx playwright test --project=at-destructive --no-deps`を
直後に実行した(4.2分、22件中10件失敗・10件成功・2件未実行)。失敗した10件も
`at-destructive`が意図的に下流サービスを止める系のシナリオで、本Issueの経路とは
無関係。**`auth/token-lifecycle.feature`「無効化したユーザーの発行済みアクセストークンは
拒否される」(Requirement 2のカテゴリ2で使い捨てアカウントへ隔離した経路)と
`auth/brute-force-lockout.feature`はいずれも成功した。**

この2回の実行(`at-main`実行→無再構築での`at-destructive`実行)を通じて
`docker logs lbs-keycloak`を確認したところ、`user_temporarily_disabled`は
使い捨てアカウント`e2e-1056-bruteforce-*`(`bruteForceLockout.feature`が意図的に
起こすロック、#1056の設計どおり)の4件のみで、**共有アカウント
(`e2e-test@letsblog.local` / `e2e-admin@letsblog.local`)には1件も発生しなかった**。

```
docker logs lbs-keycloak 2>&1 | grep "user_temporarily_disabled" \
  | grep -c "e2e-test@letsblog.local\|e2e-admin@letsblog.local"
# => 0
docker logs lbs-keycloak 2>&1 | grep "user_temporarily_disabled" \
  | grep -oP 'username="\K[^"]+' | sort -u
# => e2e-1056-bruteforce-mu3tbwgz0g9p@example.com のみ
```

**留保**: 上記は「単一の`npm run test:at:clean`コマンドが最初から最後まで例外なく
実行された」ものではない(`at-main`の無関係な既存不安定さにより、Playwrightの
依存スキップで`at-destructive`が自動起動しなかったため、2つ目のコマンドを手動で
追加実行した)。ただし**再シード・再構築は一切行っておらず、共有アカウントの
Keycloak側の状態(失敗カウントを含む)は`at-main`終了時点からそのまま連続している**
——前回差し戻しの原因だった「再シードによる失敗カウントのリセット」は発生していない。
`at-main`自体の無関係な不安定さ(上表)を解消しない限り、`at-destructive`が
Playwrightの依存機構によって自動連結される単一コマンドでの実行は得られない。

---

## 17. 参考

- [ACCEPTANCE_CRITERIA.md](ACCEPTANCE_CRITERIA.md) — 受け入れ基準カタログ(機能IDと検証状況)
- `docker-compose.e2e-stubs.yml` / `infra/e2e-stubs/` — 外部依存スタブ(§9)
- [e2e-testing.md](e2e-testing.md) — スタック起動、Keycloak プロビジョニング、テストデータ、トラブルシューティング
- [TEST_DOCUMENTATION.md](TEST_DOCUMENTATION.md) — テスト全体の階層
- [playwright-bdd](https://vitalets.github.io/playwright-bdd/)
- Epic #925 / AT-0 #926
