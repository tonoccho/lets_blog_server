import type { Locator, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken, loginAsAdmin } from '../support';

/**
 * ダッシュボードの環境設定ウィジェットのステップ定義(issue #1500)。
 * 背景は `projectEnvironment.steps.ts` の「環境・GitHub連携検証用のプロジェクトがある」
 * 「環境紐付け用のサイトが2つある」を再利用する(ctx の penv* を読む)。
 */

const ENV_HEADING = { local: 'ローカル環境', test: 'テスト環境', production: '本番環境' } as const;
type Env = keyof typeof ENV_HEADING;
const ENV_LABEL: Record<string, Env> = { ローカル: 'local', テスト: 'test', 本番: 'production' };

function widget(page: Page): Locator {
  return page.locator('div.rounded-lg').filter({ has: page.locator('h2', { hasText: /^環境設定$/ }) }).first();
}

function slot(scope: Locator, env: Env): Locator {
  return scope
    .locator('div.rounded-lg')
    .filter({ has: scope.page().getByRole('heading', { name: ENV_HEADING[env], exact: true }) })
    .first();
}

async function adminHeaders(request: import('@playwright/test').APIRequestContext) {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

Given('テスト環境にサイトがAPIで紐付いている', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const response = await request.post(`/api/projects/${ctx.penvProjectId as number}/environments`, {
    headers,
    data: { environment: 'test', siteId: ctx.penvTestSiteId as number },
  });
  expect(response.ok(), `環境の紐付けに失敗しました (status=${response.status()})`).toBe(true);
});

When('環境設定ウィジェットを見るためにダッシュボードを開く', async ({ page, ctx }) => {
  await loginAsAdmin(page);
  await page.goto(`/projects/${ctx.penvProjectId as number}/dashboard`);
  await expect(widget(page)).toBeVisible({ timeout: 30_000 });
});

Then(/^環境設定ウィジェットの(テスト|本番)環境に紐付いたサイトのキーが表示される$/, async ({ page, ctx }, label: string) => {
  const key = (label === 'テスト' ? ctx.penvTestSiteKey : ctx.penvProductionSiteKey) as string;
  await expect(slot(widget(page), ENV_LABEL[label]).getByText(key, { exact: true })).toBeVisible({ timeout: 10_000 });
});

Then(/^環境設定ウィジェットの(ローカル|本番)環境は未設定と表示される$/, async ({ page }, label: string) => {
  await expect(slot(widget(page), ENV_LABEL[label]).getByText('未設定', { exact: true })).toBeVisible();
});

Given('資格情報が誤っているサイトがテスト環境にAPIで紐付いている', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const siteKey = `e2e-1671-connfail-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
  const created = await request.post('/api/sites', {
    headers,
    data: {
      name: `E2E 1671 ${siteKey}`,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'SSH',
        baseUrl: 'http://wrong.invalid',
        sshHost: 'wrong.invalid',
        sshUser: 'nouser',
        wpPath: '/nowhere',
        sshPrivateKeyPem:
          '-----BEGIN OPENSSH PRIVATE KEY-----\ne2e-1671-fixture-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----',
      },
    },
  });
  expect(created.ok(), `サイト登録に失敗しました (status=${created.status()})`).toBe(true);
  const siteId = ((await created.json()) as { id: number }).id;
  ctx.dashConnFailSiteId = siteId;
  const bound = await request.post(`/api/projects/${ctx.penvProjectId as number}/environments`, {
    headers,
    data: { environment: 'test', siteId },
  });
  expect(bound.ok(), `環境の紐付けに失敗しました (status=${bound.status()})`).toBe(true);
});

Then('環境設定ウィジェットに紐付けのセレクトと紐付け・切離しのボタンが表示されない', async ({ page }) => {
  const scope = widget(page);
  await expect(scope.locator('select')).toHaveCount(0);
  await expect(scope.getByRole('button', { name: /紐付ける|切離し/ })).toHaveCount(0);
});

Then('環境設定ウィジェットのテスト環境に疎通確認の結果は表示されていない', async ({ page }) => {
  const test = slot(widget(page), 'test');
  await expect(test.getByRole('button', { name: '疎通確認' })).toBeVisible();
  await expect(test.getByText('FAILED', { exact: true })).toHaveCount(0);
  await expect(test.getByText('SUCCESS', { exact: true })).toHaveCount(0);
});

When('環境設定ウィジェットのテスト環境で疎通確認を押す', async ({ page }) => {
  await slot(widget(page), 'test').getByRole('button', { name: '疎通確認' }).click();
});

Then('環境設定ウィジェットのテスト環境に疎通確認の失敗と理由が表示される', async ({ page }) => {
  const test = slot(widget(page), 'test');
  await expect(test.getByText('FAILED', { exact: true })).toBeVisible({ timeout: 30_000 });
  const reason = test.locator('span.text-red-700').nth(1);
  await expect(reason).toBeVisible();
  expect(((await reason.textContent()) ?? '').trim().length).toBeGreaterThan(0);
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  const siteId = ctx.dashConnFailSiteId as number | undefined;
  if (siteId === undefined) {
    return;
  }
  await request.delete(`/api/sites/${siteId}`, { headers: await adminHeaders(request) });
});
