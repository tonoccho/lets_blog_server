import { expect } from '@playwright/test';
import { Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, fetchAccessToken } from '../support';

/**
 * ダウンロードメニューの配布物(source-distribution.feature、issue #1491 → #1749)。
 *
 * Penpotプラグインと MCPサーバーの配布は廃止した。メニューには VSCode 拡張だけが残り、
 * 廃止した配布経路(Web の /downloads/* と API の /api/system/*)は 404 を返す。
 * 「管理者としてログインする」「ビューポート幅NNNpxでダッシュボードを開く」
 * 「ヘッダーのダウンロードメニューを開く」は既存の共通ステップを再利用する。
 */

Then('ダウンロードメニューにVSCode拡張の項目だけが表示される', async ({ page }) => {
  const items = page.getByRole('menuitem');
  await expect(items).toHaveCount(1);
  await expect(items.first()).toHaveText(/VSCode拡張機能/);
});

Then('ダウンロードメニューにPenpotプラグインとMCPサーバーの項目が無い', async ({ page }) => {
  await expect(page.getByRole('menuitem', { name: /Penpotプラグイン/ })).toHaveCount(0);
  await expect(page.getByRole('menuitem', { name: /MCPサーバー/ })).toHaveCount(0);
});

When('管理者として廃止したPenpotプラグインとMCPサーバーの配布経路にアクセスする', async ({ page, request, ctx }) => {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const headers = { Authorization: `Bearer ${token}` };
  ctx.distributionStatuses = [
    (await page.request.get('/downloads/penpot-plugin')).status(),
    (await page.request.get('/downloads/mcp-server')).status(),
    (await request.get('/api/system/penpot-plugin', { headers })).status(),
    (await request.get('/api/system/mcp-server', { headers })).status(),
  ];
});

Then('どの配布経路も404を返す', async ({ ctx }) => {
  expect(ctx.distributionStatuses).toEqual([404, 404, 404, 404]);
});
