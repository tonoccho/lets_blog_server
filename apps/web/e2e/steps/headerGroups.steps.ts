import { expect } from '@playwright/test';
import { When, Then } from './fixtures';

/**
 * ヘッダーの3つのまとまり(issue #1490)。
 * 「管理者としてログインする」「ビューポート幅NNNpxでダッシュボードを開く」は既存の共通ステップを再利用する。
 */

const GROUPS = ['header-logo', 'header-account', 'header-download'] as const;

Then('ヘッダーにロゴ・ログイン情報・ダウンロードメニューの3つのまとまりが表示される', async ({ page }) => {
  for (const id of GROUPS) {
    await expect(page.locator(`header [data-testid="${id}"]`)).toBeVisible({ timeout: 15_000 });
  }
});

Then(
  'ログイン情報のまとまりにアカウントとログアウトがあり、言語切替とテーマ切替は含まれない',
  async ({ page }) => {
    const account = page.locator('header [data-testid="header-account"]');
    await expect(account).toBeVisible({ timeout: 15_000 });
    await expect(account).toContainText('@');
    await expect(account.getByRole('button', { name: 'ログアウト' })).toBeVisible();
    await expect(account.locator('[data-testid="language-switcher"]')).toHaveCount(0);
    await expect(account.locator('[data-testid="theme-switcher"]')).toHaveCount(0);
    await expect(page.locator('header [data-testid="language-switcher"]')).toBeVisible();
    await expect(page.locator('header [data-testid="theme-switcher"]')).toBeVisible();
  }
);

When('ヘッダーのダウンロードメニューを開く', async ({ page }) => {
  await page
    .locator('header [data-testid="header-download"]')
    .getByRole('button', { name: 'ダウンロード', exact: true })
    .click();
});

Then('ダウンロードメニューにVSCode拡張の項目が表示される', async ({ page }) => {
  await expect(page.getByRole('menuitem', { name: /VSCode拡張機能/ })).toBeVisible();
});

When('ダウンロードメニューのVSCode拡張を選ぶ', async ({ page, ctx }) => {
  const downloadPromise = page.waitForEvent('download', { timeout: 85_000 });
  await page.getByRole('menuitem', { name: /VSCode拡張機能/ }).click();
  ctx.headerDownload = await downloadPromise;
});

Then('.vsixファイルがダウンロードされる', async ({ ctx }) => {
  const download = ctx.headerDownload as { suggestedFilename(): string };
  expect(download.suggestedFilename()).toMatch(/\.vsix$/);
});

Then('ヘッダーの3つのまとまりがすべて画面内で操作できる', async ({ page }) => {
  const viewport = page.viewportSize()!.width;
  for (const id of GROUPS) {
    const box = await page.locator(`header [data-testid="${id}"]`).boundingBox();
    expect(box, `${id} が表示されていません`).not.toBeNull();
    expect(box!.x).toBeGreaterThanOrEqual(-1);
    expect(box!.x + box!.width).toBeLessThanOrEqual(viewport + 1);
  }
  await expect(page.locator('header [data-testid="header-download"]').getByRole('button')).toBeEnabled();
  await expect(page.locator('header [data-testid="header-account"]').getByRole('button', { name: 'ログアウト' })).toBeVisible();
});

Then('ダウンロードメニューのVSCode拡張の項目が画面内に収まっている', async ({ page }) => {
  const viewport = page.viewportSize()!.width;
  const item = page.getByRole('menuitem', { name: /VSCode拡張機能/ });
  await expect(item).toBeVisible();
  const box = await item.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(-1);
  expect(box!.x + box!.width).toBeLessThanOrEqual(viewport + 1);
});
