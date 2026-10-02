import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * サイト一覧のアイコンリンクのステップ定義(issue #1529)。
 *
 * 「ステップ定義ファイルは相乗りしない」方針(siteRegistration.steps.ts と同じ)のため、
 * フィクスチャ登録のヘルパーはこのファイル内に閉じて持つ。
 * 管理画面パスはシステム設定の既定値(`wp-admin`)を前提とし、設定は書き換えない。
 */

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

const FIXTURE_HOST = 'links1529.invalid';

Given('リンク検証用のサイトを登録しておく', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const unique = uniqueSuffix();
  const siteKey = `e2e-1529-${unique}`;
  const name = `E2E 1529 ${unique}`;
  const response = await request.post('/api/sites', {
    headers,
    data: {
      name,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'SSH',
        baseUrl: `http://${unique}.${FIXTURE_HOST}`,
        sshHost: 'wrong.invalid',
        sshUser: 'nouser',
        wpPath: '/nowhere',
        sshPrivateKeyPem:
          '-----BEGIN OPENSSH PRIVATE KEY-----\ne2e-1529-fixture-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----',
      },
    },
  });
  expect(
    response.ok(),
    `フィクスチャのサイト登録に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const created = (await response.json()) as { id: number };
  const detail = await request.get(`/api/sites/${created.id}`, { headers });
  const { baseUrl } = (await detail.json()) as { baseUrl: string };
  ctx.linkSiteId = created.id;
  ctx.linkSiteKey = siteKey;
  ctx.linkSiteName = name;
  ctx.linkSiteBaseUrl = baseUrl;
});

When('サイト一覧を読み込み直す', async ({ page }) => {
  await page.reload();
});

When('検索欄に公開URLの一部を入力する', async ({ page, ctx }) => {
  await page.getByPlaceholder('サイトキー・表示名・URLで検索').fill(ctx.linkSiteBaseUrl as string);
});

function row(ctx: Record<string, unknown>, page: import('@playwright/test').Page) {
  return page.locator(`tr:has-text("${ctx.linkSiteKey}")`);
}

Then('そのサイトの行に公開URLの文字列は表示されない', async ({ ctx, page }) => {
  await expect(row(ctx, page)).toBeVisible();
  await expect(row(ctx, page)).not.toContainText(FIXTURE_HOST);
});

Then('そのサイトの「サイトを開く」リンクは公開URLを新しいタブで開く', async ({ ctx, page }) => {
  const link = row(ctx, page).getByRole('link', { name: `${ctx.linkSiteName} のサイトを開く` });
  await expect(link).toHaveAttribute('href', ctx.linkSiteBaseUrl as string);
  await expect(link).toHaveAttribute('target', '_blank');
});

Then('そのサイトの「管理画面を開く」リンクは公開URLの wp-admin を新しいタブで開く', async ({ ctx, page }) => {
  const link = row(ctx, page).getByRole('link', { name: `${ctx.linkSiteName} の管理画面を開く` });
  const base = (ctx.linkSiteBaseUrl as string).replace(/\/$/, '');
  await expect(link).toHaveAttribute('href', `${base}/wp-admin`);
  await expect(link).toHaveAttribute('target', '_blank');
});

Then('そのサイトの行にサイト名を含む「サイトを開く」と「管理画面を開く」のリンクがある', async ({ ctx, page }) => {
  const r = row(ctx, page);
  await expect(r.getByRole('link', { name: `${ctx.linkSiteName} のサイトを開く` })).toHaveCount(1);
  await expect(r.getByRole('link', { name: `${ctx.linkSiteName} の管理画面を開く` })).toHaveCount(1);
});

Then('そのサイトの行が一覧に表示される', async ({ ctx, page }) => {
  await expect(row(ctx, page)).toBeVisible();
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  if (ctx.linkSiteId === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  await request.delete(`/api/sites/${ctx.linkSiteId}`, { headers });
});
