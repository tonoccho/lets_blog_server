import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * サイト編集画面の管理画面パス(issue #1532)のステップ定義。
 * 「ステップ定義ファイルは相乗りしない」方針のため、フィクスチャのヘルパーはここに閉じて持つ。
 */

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

async function fetchAdminPath(request: APIRequestContext, id: number): Promise<string | null> {
  const response = await request.get(`/api/sites/${id}`, { headers: await adminHeaders(request) });
  expect(response.ok(), `サイト詳細の取得に失敗しました (status=${response.status()})`).toBe(true);
  return ((await response.json()) as { adminPath?: string | null }).adminPath ?? null;
}

function pathInput(page: Page) {
  return page.locator('input[name="adminPath"]');
}

Given('管理画面パス検証用のサイトを登録しておく', async ({ request, ctx }) => {
  const unique = uniqueSuffix();
  const response = await request.post('/api/sites', {
    headers: await adminHeaders(request),
    data: {
      name: `E2E 1532 ${unique}`,
      siteKey: `e2e-1532-${unique}`,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'SSH',
        baseUrl: `http://${unique}.admin-path1532.invalid`,
        sshHost: 'wrong.invalid',
        sshUser: 'nouser',
        wpPath: '/nowhere',
        sshPrivateKeyPem:
          '-----BEGIN OPENSSH PRIVATE KEY-----\ne2e-1532-fixture-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----',
      },
    },
  });
  expect(
    response.ok(),
    `フィクスチャのサイト登録に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.adminPathSiteId = ((await response.json()) as { id: number }).id;
});

Given('管理画面パス検証用のマネージドWordPressサイトを構築しておく', async ({ ctx, page, request }) => {
  const unique = uniqueSuffix();
  const siteKey = `e2e1532-${unique}`;
  await page.locator('id=site-creation').scrollIntoViewIfNeeded();
  await page.locator('button:has-text("WordPressを新規構築")').click();
  await page.locator('input[name="managedName"]').fill(`E2E 1532 ${unique}`);
  await page.locator('input[name="managedSiteKey"]').fill(siteKey);
  await page.locator('input[name="managedTitle"]').fill(`E2E 1532 ${unique}`);
  await page
    .locator('input[name="managedAdminUser"]')
    .fill(`e2eadmin${unique}`.replace(/[^a-zA-Z0-9._-]/g, '').slice(0, 30));
  await page.locator('input[name="managedAdminEmail"]').fill(`e2e-${unique}@letsblog.local`);
  await page.locator('input[name="managedAdminPassword"]').fill('E2eProvision#Passw0rd1');
  await page.locator('button:has-text("構築する")').click();
  await expect(page.locator(`tr:has-text("${siteKey}")`)).toBeVisible({ timeout: 240000 });
  const list = await request.get('/api/sites', { headers: await adminHeaders(request) });
  const sites = (await list.json()) as { id: number; siteKey: string }[];
  const found = sites.find((s) => s.siteKey === siteKey);
  expect(found, `構築したサイト ${siteKey} が一覧APIに見つかりません`).toBeDefined();
  ctx.adminPathSiteId = found!.id;
});

Given('そのサイトの管理画面パスを{string}に設定済みである', async ({ request, ctx }, value: string) => {
  const response = await request.put(`/api/sites/${ctx.adminPathSiteId}`, {
    headers: await adminHeaders(request),
    data: { adminPath: value },
  });
  expect(response.ok(), `管理画面パスの設定に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(
    true
  );
});

When('サイト編集画面で管理画面パスに{string}を入力して保存する', async ({ ctx, page }, value: string) => {
  await page.goto(`/sites/${ctx.adminPathSiteId}/edit`);
  await pathInput(page).fill(value);
  await page.locator('button[type="submit"]').click();
  ctx.adminPathAttempted = value;
});

When('サイト編集画面で管理画面パスを空欄にして保存する', async ({ ctx, page }) => {
  await page.goto(`/sites/${ctx.adminPathSiteId}/edit`);
  await pathInput(page).fill('');
  await page.locator('button[type="submit"]').click();
  await expect(page.getByText('保存しました。')).toBeVisible({ timeout: 10000 });
});

Then('サイト編集画面を開き直すと管理画面パスの欄に{string}が入っている', async ({ ctx, page }, value: string) => {
  await expect(page.getByText('保存しました。')).toBeVisible({ timeout: 10000 });
  await page.goto(`/sites/${ctx.adminPathSiteId}/edit`);
  await expect(pathInput(page)).toHaveValue(value);
});

Then(
  'サイト編集画面を開き直すと管理画面パスの欄は空でプレースホルダにグローバル既定値が表示されている',
  async ({ ctx, page, request }) => {
    const response = await request.get('/api/system-settings/site-admin-path', {
      headers: await adminHeaders(request),
    });
    const { path } = (await response.json()) as { path: string };
    await page.goto(`/sites/${ctx.adminPathSiteId}/edit`);
    await expect(pathInput(page)).toHaveValue('');
    await expect(pathInput(page)).toHaveAttribute('placeholder', new RegExp(path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
);

Then('サイト編集画面に認証情報の入力欄は表示されない', async ({ page }) => {
  await expect(page.locator('input[name="baseUrl"]')).toHaveCount(0);
  await expect(page.locator('input[name="sshHost"]')).toHaveCount(0);
});

Then('管理画面パスのエラーが表示される', async ({ page }) => {
  await expect(page.getByText('相対パス')).toBeVisible({ timeout: 10000 });
  await expect(page.getByText('保存しました。')).toHaveCount(0);
});

Then('そのサイトの管理画面パスはAPI上で{string}のままである', async ({ request, ctx }, value: string) => {
  expect(await fetchAdminPath(request, ctx.adminPathSiteId as number)).toBe(value);
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  if (ctx.adminPathSiteId === undefined) {
    return;
  }
  await request.delete(`/api/sites/${ctx.adminPathSiteId}`, { headers: await adminHeaders(request) });
});
