import { defineConfig, devices } from '@playwright/test';
import { defineBddProject } from 'playwright-bdd';

/**
 * issue #588: 実行時間対策。バックエンドがマルチサービス化し、フィクスチャ構築
 * (ManagedWordPressの自動構築・ComfyUIでの画像生成等)を伴うspecが増えたため、
 * 全specを5ブラウザで回すと実行時間が現実的でなくなった。
 *
 * ブラウザ差が結果に影響するのは「画面のレンダリング・アクセシビリティ・認証リダイレクト」
 * 程度であり、バックエンド横断のシナリオ(サイト登録・記事公開・画像生成・縮退表示)は
 * ブラウザを変えても同じ経路を通る。そこで:
 *   - chromium         : 全specを実行する(既定の網羅ブラウザ)
 *   - その他のブラウザ : ブラウザ差が意味を持つspecのみに絞る
 * とし、実行時間を抑えつつクロスブラウザ検証の意図は維持する。
 */
const CROSS_BROWSER_SPECS = [/auth-flow\.spec\.ts/, /accessibility\.spec\.ts/];

/**
 * issue #926 (AT-0): 受け入れテストは Gherkin(`.feature`)で記述し、playwright-bdd で
 * Playwright のテストへ変換して実行する。ランナーを Playwright のままにすることで、
 * 下の globalSetup(スタックのhealthy待ち)・ignoreHTTPSErrors・trace/video・retries・
 * ワーカー制御を、受け入れテストでもそのまま使える。
 *
 * 生成物は web/.features-gen/ に出る。testDir('./e2e')の外へ置くことで、chromium 等の
 * 既存 spec 用プロジェクトが生成物を拾わないようにしている(生成物の二重実行を防ぐ)。
 * .gitignore / eslint.config.mjs / tsconfig.json の除外も併せて更新すること(#848 と同型)。
 *
 * issue #945 (AT-19): 受け入れテストは「まっさらな状態」から**段階順に**実行する。
 *
 *   reset(スクリプト) → at-setup → at-seed → at-provision → at-main
 *
 * 段階は Playwright のプロジェクト間 `dependencies` で表す。前段が失敗すると後続は
 * **実行されない**(Playwright は依存プロジェクトが落ちた場合、依存元をスキップとして
 * 報告する)。大量の失敗で原因が埋もれるのを避けるための設計であり、AC がこれを要求している。
 *
 * 段階の割り当てはタグで行う。`@stage:setup` / `@stage:provision` を付けないシナリオは
 * すべて at-main に入る。
 */
const BDD_COMMON = {
  features: 'e2e/features/**/*.feature',
  // ステップ定義とその依存(support/)を読み込む。support/ は既存 helpers.ts の再エクスポート。
  steps: ['e2e/steps/**/*.ts', 'e2e/support/**/*.ts'],
  featuresRoot: 'e2e/features',
  // `.feature` は日本語キーワード(機能:/シナリオ:/前提/もし/ならば)で書く。
  language: 'ja',
};

/** 段階1: 初回セットアップ(AT-3 / #929)。リセット直後の「ユーザー0人」に対して走る。 */
const atSetup = defineBddProject({
  ...BDD_COMMON,
  name: 'at-setup',
  outputDir: '.features-gen/at-setup',
  tags: '@stage:setup',
});

/** 段階3: WordPress のプロビジョニング(AT-5 / #931)。利用者の指示により他より先に通す。 */
const atProvision = defineBddProject({
  ...BDD_COMMON,
  name: 'at-provision',
  outputDir: '.features-gen/at-provision',
  tags: '@stage:provision',
});

/** 段階4: それ以外すべて。 */
const atMain = defineBddProject({
  ...BDD_COMMON,
  name: 'at-main',
  outputDir: '.features-gen/at-main',
  tags: 'not @stage:setup and not @stage:provision',
});

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  // 既定はCIでも1ワーカー(バックエンドを共有するため)。実行時間を優先する環境では
  // E2E_WORKERSで上書きできる。各specのフィクスチャは一意な名前(タイムスタンプ+乱数)で
  // 作られるため、並列度を上げてもデータは衝突しない。
  workers: process.env.E2E_WORKERS ? Number(process.env.E2E_WORKERS) : process.env.CI ? 1 : undefined,
  reporter: 'html',

  // テスト開始前に docker compose の全サービスがhealthyになるまで待ち、公開URLとKeycloakへの
  // 疎通を確認する(#588)。終了後はE2E_DB_CLEANUP=1のときのみ全スキーマの後片付けを行う。
  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',

  use: {
    // issue #564でKeycloak(Authorization Code + PKCE)へ移行して以降、コールバックURLは
    // Keycloakクライアント(keycloak/realm-export.jsonのletsblog-web)に
    // https://localhost/api/auth/callback/keycloak として固定登録されている。
    // このURLは実行時に差し替えられないため、E2Eも`npm run dev`(http://localhost:3000)を
    // 別途起動するのではなく、docker composeのweb+reverse-proxy+keycloakスタック
    // (https://localhost)へ直接向ける必要がある。証明書はreverse-proxyの自己署名証明書
    // (scripts/generate-certs.sh)なのでignoreHTTPSErrorsが必要。
    baseURL: 'https://localhost',
    ignoreHTTPSErrors: true,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    // 受け入れテスト(.feature)。既存 spec 用のプロジェクトとは testDir が別なので、
    // 双方が互いのテストを拾うことはない。
    //
    // ブラウザ差が結果に影響するのは AT-18(#944)の範囲だけなので、受け入れテストは
    // chromium のみで回す。上の CROSS_BROWSER_SPECS と同じ考え方で、ブラウザ別の
    // 受け入れテストが必要になった時点で AT-18 がプロジェクトを追加する。
    //
    // `--project=at-main` を指定すれば、依存する at-setup → at-seed → at-provision も
    // Playwright が自動で先に実行する。段階を個別に指定する必要はない。
    {
      ...atSetup,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      // 段階2: 合成アカウントの発行(scripts/seed-acceptance-env.sh)。
      // Gherkin ではなく通常の Playwright テストにしてある。受け入れ「基準」ではなく
      // 環境構築の手順であり、利用者から見たふるまいを持たないため。
      name: 'at-seed',
      testDir: './e2e/stages',
      testMatch: /seed\.setup\.ts/,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-setup'],
    },
    {
      ...atProvision,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-seed'],
    },
    {
      ...atMain,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-provision'],
    },
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
      testMatch: CROSS_BROWSER_SPECS,
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
      testMatch: CROSS_BROWSER_SPECS,
    },
    {
      name: 'Mobile Chrome',
      use: { ...devices['Pixel 5'] },
      testMatch: CROSS_BROWSER_SPECS,
    },
    {
      name: 'Mobile Safari',
      use: { ...devices['iPhone 12'] },
      testMatch: CROSS_BROWSER_SPECS,
    },
  ],

  // webServerは持たない。上記の通りKeycloakのredirect_uriがhttps://localhost固定のため、
  // docker compose(web/reverse-proxy/keycloak/gateway/各ドメインサービス一式)が既に
  // 起動していることを前提とする(`docker compose up -d`。README/docs/setup.md、
  // docs/e2e-testing.md参照)。Playwright自身にサーバーを起動させる旧来の方式
  // (npm run dev on :3000)は、別ポート・別プロトコルになり登録済みredirect_uriと
  // 一致しなくなるため使えない。起動待ちはglobalSetupが担う。
});
