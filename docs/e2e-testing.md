# E2E テストガイド(マルチサービス構成)

Playwright による Let's Blog Server の E2E テストの実行方法・前提条件・保守方法をまとめる。

> **重要(issue #588)**: 認証は Keycloak(ホスト型ログイン画面)へ、バックエンドは
> gateway + 複数のドメインサービスへ移行済み。E2E は `npm run dev`(`http://localhost:3000`)
> ではなく、**docker compose で起動したスタック全体(`https://localhost`)** に対して実行する。

> **受け入れテスト(issue #926 / AT-0)**: 受け入れ基準は Gherkin(`.feature`)で書き、
> playwright-bdd で同じ Playwright スタック上で実行する。記述形式・配置規約・タグ規約・
> 実行方法、および本ドキュメントが扱う spec からの移行方針は
> **[ACCEPTANCE_TESTING.md](ACCEPTANCE_TESTING.md)** を参照。
> 本ドキュメントは前提環境(スタック起動・プロビジョニング・テストデータ)を扱う。

---

## 1. 全体像

| 項目 | 内容 |
| --- | --- |
| ベースURL | `https://localhost`(reverse-proxy の自己署名証明書。`ignoreHTTPSErrors: true`) |
| 認証 | Keycloak のホスト型ログイン画面(NextAuth の Authorization Code + PKCE) |
| テスト対象 | web(Next.js) / gateway / identity / project / content / media / ai / analytics / platform / publishing / legacy-api / Keycloak / MySQL / RabbitMQ / WordPress |
| テスト配置 | `apps/web/e2e/` |
| 設定 | `apps/web/playwright.config.ts` |
| 起動待ち | `apps/web/e2e/global-setup.ts` → `scripts/wait-for-stack-healthy.sh` |
| 後片付け | `apps/web/e2e/global-teardown.ts` → `scripts/e2e-cleanup-test-data.sh`(任意) |

E2E は Playwright に Web サーバーを起動させない(`webServer` を設定していない)。
Keycloak クライアント `letsblog-web` の redirect_uri が
`https://localhost/api/auth/callback/keycloak` に固定登録されているため、
別ポート・別プロトコルでは認証フローが成立しないため。

---

## 2. テストファイル

`apps/web/e2e/`:

| ファイル | 内容 | ログイン |
| --- | --- | --- |
| `features/**/*.feature` | **受け入れテスト**(Gherkin)。[ACCEPTANCE_TESTING.md](ACCEPTANCE_TESTING.md) | シナリオによる |
| `steps/*.ts` | 受け入れテストのステップ定義 | - |
| `support/index.ts` | ステップ定義から `helpers.ts` を参照するための再エクスポート | - |
| `helpers.ts` | 共通ヘルパー(Keycloak ログイン、トークン取得、compose 操作、healthy 待ち) | - |
| `global-setup.ts` | 全サービスの healthy 待ち + 公開URL/Keycloak への疎通確認 | - |
| `global-teardown.ts` | 全スキーマ横断のテストデータ削除(`E2E_DB_CLEANUP=1` のときのみ) | - |
| `main-scenario.spec.ts` | **主要シナリオ**: サイト登録 → 記事公開 → 履歴確認 | admin |
| `site-registration.spec.ts` | サイト管理・疎通確認(ManagedWordPress フィクスチャ) | admin |
| `post-creation.spec.ts` | プロジェクト作成ワークフロー | admin |
| `image-upload.spec.ts` | 画像ギャラリー(ComfyUI 生成フィクスチャ) | admin |
| `accessibility.spec.ts` | アクセシビリティ(アプリ画面 + Keycloak ログイン画面) | user |
| `custom-tag-generation.spec.ts` | カスタムタグ生成(プロジェクト詳細タブ) | - |
| `performance.spec.ts` | カスタムタグ検証 API の応答時間とタグ画面のページロード性能 | admin |
| `security.spec.ts` | XSS/CSS インジェクション検出、テンプレート削除の認可、CSRF・SQL インジェクション対策 | 両方 |

---

## 3. 事前準備

### 3.1 スタックの起動

```bash
# プロジェクトルート
cp .env.example .env      # 初回のみ。値を埋める
./scripts/generate-certs.sh   # 初回のみ(自己署名証明書)
docker compose up -d

# 全サービスが healthy になるまで待つ(E2E の globalSetup と同じ判定)
./scripts/wait-for-stack-healthy.sh
```

`wait-for-stack-healthy.sh` は E2E に必要なサービス
(reverse-proxy / web / gateway / keycloak / keycloak-postgres / mysql / rabbitmq /
identity / media / ai / content / analytics / project / publishing / platform /
log-writer)を対象に、`healthy`(ヘルスチェックを持たないものは `running`)
になるまで待つ。penpot / comfyui / drawio 等の任意サービスは待機対象に含めない。

```bash
./scripts/wait-for-stack-healthy.sh --all --timeout 900   # 全コンテナを対象にする
./scripts/wait-for-stack-healthy.sh --services "gateway keycloak"
```

### 3.2 テストユーザー / E2E 専用クライアント(Keycloak)のプロビジョニング

E2E は実ユーザー(`s.tonouchi@gmail.com` 等)を **使わない**。専用の合成アカウントを使う。

| アカウント | ロール | 用途 |
| --- | --- | --- |
| `e2e-test@letsblog.local` | user | 非 admin 側の検証 |
| `e2e-admin@letsblog.local` | admin(realm ロール `admin` 付き) | admin 操作の検証 |

ローカル開発の Keycloak コンテナ(`lbs-keycloak`)へは、スクリプトで発行できる。

```bash
E2E_PROVISION_ADMIN_EMAIL='<letsblog realm の管理者アカウント>' \
E2E_PROVISION_ADMIN_PASSWORD='<その管理者のパスワード>' \
E2E_TEST_PASSWORD='<任意の強いパスワード>' \
E2E_ADMIN_PASSWORD='<任意の強いパスワード>' \
./scripts/provision-e2e-keycloak-users.sh
```

> **前提**: letsblog realm に admin ロールのユーザーが既に存在すること。
> ユーザー作成 (`POST /api/users`) は issue #796 で admin 限定になったため、
> スクリプトは実在する管理者のトークンで API を呼ぶ。ローカル開発環境では初回セットアップ
> (legacy-api の `POST /api/auth/setup`。`users` が空のときだけ許可される公開パス)で作られた
> 管理者アカウントが該当する。**まだ管理者が1人もいない環境では、先にその初回セットアップを
> 済ませてから**このスクリプトを実行すること。
> パスワードは `.env` には保存せず、実行時に環境変数で渡す。

このスクリプトは以下を行う(冪等。既存アカウントには作成をスキップして password/role のみ整える)。

0. E2E 専用クライアント `letsblog-e2e` を用意し、その password グラントで **実在する admin
   ユーザー** のアクセストークンを取得する。issue #772 で認証ゲート(有効な JWT が無ければ 401)が、
   issue #796 で `POST /api/users` の admin 必須が入ったため、ユーザー作成には管理者のトークンが要る。
   取得後、`GET /api/users`(admin 限定)で実際に管理者操作ができることを先に確認し、
   できなければその場で中止する(後続が 403 で落ちた理由を追いにくくしないため)。
1. `POST https://localhost/api/users`(gateway → identity-service)でユーザーを作成する。
   identity-service が **Keycloak 側のユーザー** と **ローカル DB の `lbs_identity.users`
   (`keycloak_sub` 付き)** の両方を作る。両方揃っていないと admin 操作は 403 になる
   (`CurrentActorService` が JWT の `sub` からローカル User を引くため)。
2. Keycloak Admin CLI でパスワードを設定する(`temporary=false`)。
3. admin アカウントに realm ロール `admin` を付与する(JWT の `realm_access.roles` に載る)。
4. (手順 0 で作成済み)E2E 専用クライアント `letsblog-e2e`(public / direct access grant 可)。
   `main-scenario.spec.ts` が API を直接叩くときのトークン発行に使う(§7 参照)。
   `infra/keycloak/realm-export.json` にも同じ定義があるが、Keycloak は realm export を
   **初回起動時にしか読まない** ため、既に起動済みの環境ではこのスクリプトで作る必要がある。
   既存の `admin-cli` などの実運用クライアントには一切触れない。

> **共有 / 本番 Keycloak では実行しないこと。**
> スクリプトはコンテナ名 `lbs-keycloak` 固定で、任意の URL を指定するオプションを持たない。
> 操作対象も `e2e-*@letsblog.local` に限定されており、それ以外のメールアドレスを渡すと中止する。
> 共有環境では同等の手順(サービストークンを取得 → identity-service でユーザー作成 →
> Admin Console でパスワード設定 →
> realm ロール `admin` 付与 → `letsblog-e2e` クライアント作成)を管理者が手動で行う。

パスワードはリポジトリの `.env` には保存せず、実行時に環境変数で渡す。

```bash
export E2E_TEST_PASSWORD='...'
export E2E_ADMIN_PASSWORD='...'
```

未設定の場合、ログインを要する spec は `test.skip` により **明示的にスキップ** される
(暗黙に成功したことにはならない)。

---

## 4. 実行

```bash
cd apps/web
npm install
npx playwright install    # 初回のみ

npm run test:e2e                       # 全spec(chromium は全件、他ブラウザは対象を絞る)
npm run test:e2e:ui                    # Playwright Test UI
npm run test:e2e:debug                 # Inspector
npm run test:a11y                      # アクセシビリティのみ

npm run test:at                        # 受け入れテスト(.feature)を全件
npm run test:at:fast                   # 受け入れテストから @slow / @destructive を除く
npm run test:at:clean                  # 環境をリセットしてから段階順に全実行(破壊的)

npx playwright test --project=chromium              # ブラウザを絞る
npx playwright test e2e/main-scenario.spec.ts       # ファイルを絞る
npx playwright test -g "サイトを登録して記事を公開"     # テスト名で絞る
```

`globalSetup` が実行前に以下を行うため、`docker compose up -d` の直後でもそのまま実行してよい。

1. `scripts/wait-for-stack-healthy.sh` で全サービスの healthy を待つ
2. `https://localhost/` へ HTTP リクエストして到達性を確認する
3. `https://localhost/auth/realms/letsblog/.well-known/openid-configuration` で Keycloak を確認する

いずれかが失敗した場合、テストは1件も実行されずに前提エラーとして終了する。

---

## 5. 環境変数

| 変数 | 既定 | 用途 |
| --- | --- | --- |
| `E2E_TEST_PASSWORD` | (なし) | `e2e-test@letsblog.local` のパスワード。未設定なら該当 spec をスキップ |
| `E2E_ADMIN_PASSWORD` | (なし) | `e2e-admin@letsblog.local` のパスワード。未設定なら該当 spec をスキップ |
| `E2E_SKIP_HEALTH_WAIT` | (なし) | `1` で globalSetup の healthy 待ちをスキップ(docker CLI が無い環境等) |
| `ACCEPTANCE_RESET` | (なし) | `1` で受け入れテスト環境をリセットしてから開始(破壊的。issue #945。[ACCEPTANCE_TESTING.md](ACCEPTANCE_TESTING.md) §10) |
| `COMPOSE_PROJECT_NAME` | リポジトリのディレクトリ名 | healthy 待ちが対象とする compose プロジェクト(issue #842) |
| `E2E_REQUIRE_LLM` | (なし) | `1` でLLM生成の失敗をスキップせず失敗させる(issue #843) |

### 外部依存スタブ(issue #843 → #928 で集約)

受け入れテストは外部SaaSに依存しない。LLM / Google Analytics / AdSense / Brave Search /
OpenAI画像生成 / GitHub は、決定的に応答するスタブへ置き換える。

```bash
# スタブを重ねて起動する
docker compose -f docker-compose.yml -f docker-compose.e2e-stubs.yml up -d

# LLM の接続設定がDBに残っていると環境変数より優先されるため、先に空にする
./scripts/e2e-clear-llm-db-overrides.sh

# 生成が失敗したらスキップせず落とす(未検証へ戻ったことに気づけるようにする)
E2E_REQUIRE_LLM=1 npx playwright test e2e/custom-tag-generation.spec.ts
```

スタブの一覧・応答内容・エラー注入の方法は
**[ACCEPTANCE_TESTING.md の「外部依存スタブ」節](ACCEPTANCE_TESTING.md)** にある。

`docker-compose.e2e-llm-stub.yml`(#843)は `docker-compose.e2e-stubs.yml` へ統合し削除した。
依存ごとにオーバーライドを増やすと起動手順が破綻するため、1本にまとめている。

> **`E2E_SKIP_HEALTH_WAIT` は常用しないこと。** これは docker CLI が無い環境向けの逃げ道であり、
> 常用すると本来この待ち合わせが防いでいる「まだ起動しきっていないスタックに対してテストを流す」
> 事故を検出できなくなる。
>
> issue #842 以前は `bin/loop test e2e` がこの変数なしでは必ずタイムアウトしていた。
> `scripts/wait-for-stack-healthy.sh` が `docker compose ps` を `-f` / `-p` なしで実行しており、
> カレントディレクトリに compose ファイルが無い環境(`test-e2e` コンテナの中など)では
> プロジェクトを解決できず、全サービスを「存在しない」と報告して600秒待っていた。
> 現在はリポジトリ基準の絶対パスとプロジェクト名を明示して解決するため、
> **カレントディレクトリに依存しない**。プロジェクト名がずれている場合は
> 600秒待たずに即座に、実在するプロジェクト名を添えて失敗する。
| `E2E_HEALTH_TIMEOUT` | `600` | healthy 待ちのタイムアウト秒数 |
| `E2E_DB_CLEANUP` | (なし) | `1` で globalTeardown が全スキーマのテストデータを削除する |
| `E2E_WORKERS` | (なし) | Playwright のワーカー数を明示指定する |
| `CI` | (なし) | リトライ2回・`test.only` 禁止 |

---

## 6. テストデータ(複数スキーマ)

サービス別スキーマ分離(#570 / ADR-0004)により、1つの E2E フィクスチャは複数スキーマに行を作る。

| スキーマ | 主なテーブル |
| --- | --- |
| `lbs_project` | `projects` / `sites` / `static_content` / `tag_design_settings` |
| `lbs_content` | `posts` / `custom_tags` / `custom_tag_templates` / `project_content_settings` |
| `lbs_media` | `generated_images` / `diagrams` / `project_image_settings` / `generated_image_sequences` |
| `lbs_ai` | `article_plan_sessions` / `project_ai_settings` |
| `lbs_analytics` | `analytics_credentials` |
| `lbs_identity` | `users` / `roles` / `role_permissions` / `user_roles` / `project_users` / `user_site_authors` |

投入は各 spec の `beforeAll` / `beforeEach` が **UI 経由**(=本番と同じ経路)で行い、
削除も `afterEach` / `afterAll` が UI 経由で行う。ただしフィクスチャ構築の途中で
テストが落ちた場合や、UI に削除手段が無いテーブルには孤児行が残る。
その掃除は次のスクリプトが担う。

```bash
./scripts/e2e-cleanup-test-data.sh          # ドライラン(件数を表示するだけ)
./scripts/e2e-cleanup-test-data.sh --yes    # 実際に削除する
```

対象は E2E の命名規約に一致する行のみ。

- `projects`: `slug` が `e2e-*` / `test-project-*`、または `name` が `E2E *`
- `sites`: `site_key` が `e2e*`
- 他スキーマ: 上記 projects / sites の id に紐づく行、または E2E 固有プレフィックスを持つ行

手動で作成した実データには一致しない。`E2E_DB_CLEANUP=1` を指定した実行では、
globalTeardown がこのスクリプトを `--yes` 付きで自動実行する
(**共有環境では指定しないこと**)。

ManagedWordPress サイト(`sites.managed_wordpress = 1`)については、DB 行を消す前に
wordpress コンテナ内のプロビジョニングエージェント(`POST /deprovision`、
`infra/wordpress/provision-agent/index.php`)を呼んで **実体(サイトディレクトリと専用 DB)も解放する**
(issue #765)。project-service のサイト削除が呼ぶものと同じエンドポイントで、
`rm -rf` と `DROP DATABASE IF EXISTS` はどちらも冪等なため、UI 経由で既に削除済みのサイトに
対して再実行しても問題ない。

> **前提**: `lbs-wordpress` コンテナが起動していて、`.env` に `WP_PROVISION_TOKEN` が
> 設定されていること。どちらか欠けている場合、実体の解放だけを警告付きでスキップし、
> DB 行の削除は続行する(実体は残るため、後から手動で `/deprovision` を叩く必要がある)。
> 解放に失敗したサイトについては、DB 行の削除で `wp_slug` / `wp_db_name` が失われる前に、
> 手動で解放するためのコマンドをログへ出力する。

---

## 7. 主要シナリオ

`main-scenario.spec.ts` が「サイト登録 → 記事公開 → 履歴確認」を1本で通す。

1. Keycloak のホスト型ログイン画面で admin としてサインイン
2. `/sites` から ManagedWordPress サイトを新規構築(project-service + wordpress コンテナ)
3. `POST /api/posts/publish` を gateway 経由で呼び出して記事を公開(publishing-service)
4. `/posts` の投稿履歴に反映されていることを確認(content-service)
5. 投稿とサイトを削除して後片付け

記事公開は **Web UI に存在しない機能**(通常は VSCode 拡張が gateway 経由で呼ぶ)なので、
その部分だけ API を直接呼ぶ。トークンはブラウザと同じ Keycloak ユーザーで、
E2E 専用クライアント `letsblog-e2e`(public / direct access grant 可)から
Resource Owner Password Credentials グラントで取得する(`helpers.ts` の `fetchAccessToken`)。
ローカル開発スタック専用の手段であり、アプリケーションの認証フローには影響しない。

> realm 既定の `admin-cli` は使わない。Keycloak の既定で
> `client.use.lightweight.access.token.enabled=true` が付いており、発行される
> アクセストークンから `sub` と `realm_access.roles` が落ちる。その状態では
> identity-service の `/api/identity/me` が 403 になり、publishing-service が 502 を返して
> 記事公開が失敗する。`letsblog-e2e` はこの属性を持たないため、ブラウザ経由の
> Authorization Code フローと同じ内容のトークンが得られる。

WordPress の自動構築に数分かかるため、このテストのタイムアウトは 600 秒に設定している。

---

## 8. サービス障害時の縮退表示

`apps/web/e2e/features/cross-cutting/service-degradation.feature`(issue #943 / AT-17 で
`service-degradation.spec.ts` から移行、spec は削除済み)。Web の各ページはサーバーコンポーネントで
`listPosts().catch(() => [])` のように下流エラーを吸収し、空状態へ縮退する実装になっている。

6シナリオがある。ダッシュボードの状態更新 API(ポーリング / SSE)を 503 に差し替える1件と、
実際に `docker compose stop` する5件(content / ai / media / log-writer の停止と、content の復旧)。

いずれも `@destructive` なので、`at-destructive` 段階で**最後にそれだけで**実行される
(docs/ACCEPTANCE_TESTING.md §10)。停止したサービスはシナリオの後始末で必ず起動し直し、
healthy になるまで待つ。`E2E_ALLOW_SERVICE_DISRUPTION` によるオプトインは廃止した
(段階分離が同じ役割を果たし、環境変数が無いと黙ってスキップされる方が危険なため)。

スタックを一時的に壊す操作を伴うため、2 は既定で無効。CI や使い捨て環境でのみ有効化する。

---

## 9. 実行時間対策

サービス数とフィクスチャ構築(ManagedWordPress / ComfyUI)により実行時間が増えたため、
`playwright.config.ts` で以下の方針を採る。

- `chromium`: 全 spec を実行する(網羅ブラウザ)
- `firefox` / `webkit` / `Mobile Chrome` / `Mobile Safari`:
  ブラウザ差が意味を持つ `accessibility.spec.ts` のみ
- `fullyParallel: true`。フィクスチャ名はタイムスタンプ+乱数で一意なので、**名前は**並列でも衝突しない
  (ただし後述の通り、名前が衝突しないことと並列実行して安全なことは別問題)
- ワーカー数は `E2E_WORKERS` で上書き可能(既定は CI で1、ローカルは Playwright の自動判定)

さらに絞りたい場合:

```bash
npx playwright test --project=chromium e2e/main-scenario.spec.ts e2e/security.spec.ts
```

### 9.1 フィクスチャの並列実行に関する制約(issue #765)

`fullyParallel: true` では、1つの spec ファイル内のテストが複数ワーカーへ分配される。
このとき **`beforeAll` は「ファイルにつき1回」ではなく「ワーカーにつき1回」実行される**。
ManagedWordPress を `beforeAll` で構築する spec をそのまま並列実行すると、ワーカー数だけ
同時に自動構築が走り、構築が競合してタイムアウトし、削除されない孤児サイト
(`lbs_project.sites` の `e2efix-*`)が溜まる。

そのため、**ManagedWordPress を構築する describe は `test.describe.configure({ mode: 'serial' })`
を宣言する**。describe 内の全テストが1ワーカーで順に実行され、フィクスチャの構築・削除は1回だけになる。
他の spec ファイルとの並列実行は従来どおり行われるため、全体の実行時間への影響は小さい。

| spec | フィクスチャ | 実行モード |
| --- | --- | --- |
| `main-scenario.spec.ts` | ManagedWordPress サイト1件(テスト内で構築・削除) | serial |
| `site-registration.spec.ts` | ManagedWordPress サイト1件(`beforeAll` / `afterAll`) | serial |
| `post-creation.spec.ts` | プロジェクト1件(`beforeEach` / `afterEach`) | 既定(並列可) |

`post-creation.spec.ts` は ManagedWordPress を構築しないため直列化していない。ただし
`/projects` の一覧は全ワーカー・全 spec で共有されるため、**一覧の「先頭行」を対象にする
アサーションを書かないこと**(他のテストが並列に作成・削除している行を掴み、クリック直前に
行が消えて不安定になる)。必ず自分のフィクスチャを名前で特定する。

ManagedWordPress の削除は「コンテナ内のファイル削除 + 専用 DB の `DROP DATABASE`」を伴い、
30 秒では終わらないことがある。後片付けの待ちは 60 秒を目安にする
(`main-scenario.spec.ts` / `site-registration.spec.ts` はいずれも 60 秒)。
削除しきれなかった場合は `[E2E ORPHAN] site_key=...` をログへ出力するので、
実行後に孤児が残ったかどうかはレポートの標準出力から判別できる。

---

## 10. 新しいテストを書く

```typescript
import { test, expect } from '@playwright/test';
import { E2E_ADMIN_PASSWORD, loginAsAdmin } from './helpers';

test.describe('機能名', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);   // Keycloakのホスト型ログイン画面を通過する
    await page.goto('/sites');
  });

  test('説明的なテスト名', async ({ page }) => {
    await expect(page.locator('h1:has-text("サイト")')).toBeVisible();
  });
});
```

### 原則

1. **ログインは必ず `helpers.ts` 経由**(`loginAsAdmin` / `loginAsUser` / `loginViaKeycloak`)。
   spec ごとにアカウント定数を再定義しない。
2. **条件付きアサーションを書かない**。`if (要素があれば assert)` は、データが無い環境で
   何も検証しないまま pass する(issue #645)。フィクスチャを投入して確定的に検証するか、
   `test.skip` で明示的にスキップする。
3. **フィクスチャは必ず後片付けする**。名前にはタイムスタンプ+乱数を含め、
   `e2e` プレフィックス(cleanup スクリプトの対象)に合わせる。
4. **ハードコードされた待機を使わない**。`waitForTimeout` ではなく
   `expect(...).toBeVisible()` などの条件待ちを使う。
5. **長時間フィクスチャには `test.setTimeout()`** を明示する
   (フックの既定タイムアウトは 30 秒)。
6. **`beforeAll` でフィクスチャを構築する describe は `test.describe.configure({ mode: 'serial' })`
   を宣言する**。`beforeAll` はワーカーごとに実行されるため、宣言しないと同じフィクスチャが
   ワーカー数だけ重複構築される(§9.1、issue #765)。
7. **共有一覧の「先頭行」に依存しない**。他のワーカー・他の spec が同じ一覧へ行を作り消しているため、
   `tbody tr` の `.first()` は不安定。自分のフィクスチャを名前・キーで特定する。

---

## 11. トラブルシューティング

### globalSetup が「healthy になっていません」で失敗する

```bash
docker compose ps
docker compose logs <service> | tail -50
./scripts/wait-for-stack-healthy.sh --timeout 900
```

`web` は開発モード(`next dev`)のため初回リクエストのコンパイルに時間がかかる。
ヘルスチェックには `start_period: 60s` を設定済みだが、マシンが遅い場合は
`E2E_HEALTH_TIMEOUT` を伸ばす。

### すべてのログインテストがスキップされる

`E2E_TEST_PASSWORD` / `E2E_ADMIN_PASSWORD` が未設定。3.2 を参照。

### Keycloak のログイン画面で "Invalid username or password"

アカウントが未発行、またはパスワードが環境変数と一致していない。
`./scripts/provision-e2e-keycloak-users.sh` を同じパスワードで再実行する(冪等)。

### admin 操作が 403 になる

Keycloak にはユーザーがいるが、ローカル DB(`lbs_identity.users`)に `keycloak_sub` 付きの
行が無い可能性がある。identity-service 経由(`POST /api/users`)で作成し直す。

### 証明書エラー

`playwright.config.ts` の `ignoreHTTPSErrors: true` が効いているか確認する。
ブラウザ以外(`request` フィクスチャ)も同じ設定を継承する。

### テストデータが増え続ける

```bash
./scripts/e2e-cleanup-test-data.sh          # まずドライランで確認
./scripts/e2e-cleanup-test-data.sh --yes
```

### 孤児の ManagedWordPress サイト(`e2efix-*` / `e2emain-*`)が残る

後片付けが完走しなかった実行では、ログに `[E2E ORPHAN] site_key=...` が出力される。
残っているかどうかは次で確認でき、上記のクリーンアップスクリプト(`--yes`)が
DB 行と実体の両方を解放する(§6)。

```bash
docker exec -i -e MYSQL_PWD="$(grep '^MYSQL_ROOT_PASSWORD=' .env | cut -d= -f2-)" lbs-mysql \
  mysql -u root -e "SELECT site_key FROM lbs_project.sites WHERE site_key LIKE 'e2e%';"
```

---

## 12. 既知の課題

- CI(GitHub Actions)は現在無効化されているため、E2E はローカル実行が前提。

---

## 13. 参考

- `docs/e2e-validation-guide.md` — 手動でのエンドツーエンド動作検証手順
- `docs/DOCKER_COMPOSE_ARCHITECTURE.md` — サービス構成
- `docs/TEST_DOCUMENTATION.md` — サービス別のテスト戦略
- `infra/keycloak/README.md` — realm 定義と運用
- [Playwright Documentation](https://playwright.dev/)
