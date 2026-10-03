import type { APIRequestContext, Locator, Page } from '@playwright/test';
import { After, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * サイト登録フォームの管理画面パス(issue #1533)のステップ定義。
 * 「ステップ定義ファイルは相乗りしない」方針のため、ヘルパーはここに閉じて持つ。
 * 登録は画面操作で行い、結果は GET /api/sites で確認する。到達不能な SSH ホストを使う
 * (疎通確認は失敗するが登録は完了する)。
 */

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

type ListedSite = { id: number; siteKey: string; adminPath?: string | null };

async function findSites(request: APIRequestContext, siteKey: string): Promise<ListedSite[]> {
  const response = await request.get('/api/sites', { headers: await adminHeaders(request) });
  expect(response.ok(), `サイト一覧の取得に失敗しました (status=${response.status()})`).toBe(true);
  const body = (await response.json()) as ListedSite[] | { content: ListedSite[] };
  const sites = Array.isArray(body) ? body : body.content;
  return sites.filter((s) => s.siteKey === siteKey);
}

async function openRegisterForm(page: Page): Promise<Locator> {
  await page.goto('/sites');
  const form = page.locator('form', { hasText: 'サイトを登録' });
  await expect(form).toBeVisible({ timeout: 30000 });
  return form;
}

async function fillAndRegister(page: Page, ctx: Record<string, unknown>, adminPath: string | null) {
  const unique = uniqueSuffix();
  const siteKey = `e2e-1533-${unique}`;
  ctx.regAdminPathSiteKey = siteKey;
  const form = await openRegisterForm(page);
  await form.locator('input[name="name"]').fill(`E2E 1533 ${unique}`);
  await form.locator('input[name="siteKey"]').fill(siteKey);
  await form.locator('input[name="baseUrl"]').fill(`http://${unique}.admin-path1533.invalid`);
  await form.locator('input[name="sshHost"]').fill('wrong.invalid');
  await form.locator('input[name="sshUser"]').fill('nouser');
  await form.locator('input[name="wpPath"]').fill('/nowhere');
  if (adminPath !== null) {
    await form.locator('input[name="adminPath"]').fill(adminPath);
  }
  // 保存済みの鍵ペアがあれば既定で選択済み。無ければ生成する。
  if ((await form.locator('select[name="sshKeyPairId"]').count()) === 0) {
    await form.getByRole('button', { name: 'SSH鍵ペアを生成' }).click();
    await expect(form.locator('textarea')).toBeVisible({ timeout: 30000 });
  }
  await form.getByRole('button', { name: '登録', exact: true }).click();
}

When('サイト登録フォームで管理画面パスに{string}を入力して登録する', async ({ page, ctx }, value: string) => {
  await fillAndRegister(page, ctx, value);
});

When('サイト登録フォームで管理画面パスを空欄のまま登録する', async ({ page, ctx }) => {
  await fillAndRegister(page, ctx, '');
});

Then('登録されたサイトのAPI上の管理画面パスは{string}である', async ({ page, request, ctx }, value: string) => {
  await expect(page.getByText('登録しました。')).toBeVisible({ timeout: 60000 });
  const [site] = await findSites(request, ctx.regAdminPathSiteKey as string);
  expect(site, '登録したサイトが一覧APIに見つかりません').toBeDefined();
  expect(site.adminPath ?? null).toBe(value);
});

Then('登録されたサイトのAPI上の管理画面パスはnullである', async ({ page, request, ctx }) => {
  await expect(page.getByText('登録しました。')).toBeVisible({ timeout: 60000 });
  const [site] = await findSites(request, ctx.regAdminPathSiteKey as string);
  expect(site, '登録したサイトが一覧APIに見つかりません').toBeDefined();
  expect(site.adminPath ?? null).toBeNull();
});

Then('管理画面パスのエラーがサイト登録フォームに表示される', async ({ page }) => {
  await expect(page.getByText('相対パス')).toBeVisible({ timeout: 30000 });
  await expect(page.getByText('登録しました。')).toHaveCount(0);
});

Then('そのサイトキーのサイトはAPI上に登録されていない', async ({ request, ctx }) => {
  expect(await findSites(request, ctx.regAdminPathSiteKey as string)).toHaveLength(0);
});

Then(
  'サイト登録フォームの管理画面パス欄は空でプレースホルダにグローバル既定値が表示されている',
  async ({ page, request }) => {
    const response = await request.get('/api/system-settings/site-admin-path', {
      headers: await adminHeaders(request),
    });
    const { path } = (await response.json()) as { path: string };
    const form = await openRegisterForm(page);
    const input = form.locator('input[name="adminPath"]');
    await expect(input).toHaveValue('');
    await expect(input).toHaveAttribute('placeholder', new RegExp(path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
);

After({ tags: '@project' }, async ({ ctx, request }) => {
  const siteKey = ctx.regAdminPathSiteKey as string | undefined;
  if (!siteKey) {
    return;
  }
  const headers = await adminHeaders(request);
  for (const site of await findSites(request, siteKey)) {
    await request.delete(`/api/sites/${site.id}`, { headers });
  }
});
