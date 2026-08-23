import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'html',
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
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
    },
    {
      name: 'Mobile Chrome',
      use: { ...devices['Pixel 5'] },
    },
    {
      name: 'Mobile Safari',
      use: { ...devices['iPhone 12'] },
    },
  ],

  // webServerは持たない。上記の通りKeycloakのredirect_uriがhttps://localhost固定のため、
  // docker compose(web/reverse-proxy/keycloak一式)が既に起動していることを前提とする
  // (`docker compose up -d`。README/docs/setup.md参照)。Playwright自身にサーバーを
  // 起動させる旧来の方式(npm run dev on :3000)は、別ポート・別プロトコルになり
  // 登録済みredirect_uriと一致しなくなるため使えない。
});
