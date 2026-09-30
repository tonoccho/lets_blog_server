import { expect, type Page } from '@playwright/test';
import { Given, Then } from './fixtures';

/**
 * ダークモードの判定信号の一本化(issue #1239)。
 *
 * テーマ初期化スクリプトが走らない状態を、JavaScript無効のコンテキストで再現する。
 * `/login` はKeycloakへの遷移をJSで行うため、JS無効ならSSR済みのレイアウトがそのまま残る。
 * (`/login/theme-check` はNext既定の404画面で独自の配色を持つため、観測先に使えない。)
 */
const OBSERVED_PATH = '/login';

Given(
  /^JavaScriptを無効にしOSの配色設定が「(dark|light)」のブラウザでログイン画面を開く$/,
  async ({ browser, baseURL, ctx }, scheme: 'dark' | 'light') => {
    const context = await browser.newContext({ baseURL, javaScriptEnabled: false, colorScheme: scheme });
    const page = await context.newPage();
    await page.goto(OBSERVED_PATH, { waitUntil: 'load' });
    ctx.noJsPage = page;
    ctx.noJsContext = context;
  }
);

Then('html要素にdata-theme属性が付いていない', async ({ ctx }) => {
  const page = ctx.noJsPage as Page;
  await expect(page.locator('html')).not.toHaveAttribute('data-theme', /.*/);
});

Then(/^body要素の背景色は「(.+)」である$/, async ({ ctx }, expected: string) => {
  const page = ctx.noJsPage as Page;
  await expect(page.locator('body')).toHaveCSS('background-color', expected);
  await (ctx.noJsContext as { close(): Promise<void> }).close();
});
