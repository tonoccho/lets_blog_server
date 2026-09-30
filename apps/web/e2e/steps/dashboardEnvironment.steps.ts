import type { Locator, Page } from '@playwright/test';
import { Given, Then, When } from './fixtures';
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

When('環境設定ウィジェットで本番環境にサイトを紐付ける', async ({ page, ctx }) => {
  const production = slot(widget(page), 'production');
  await production
    .locator('select[aria-label="本番環境に紐付けるサイト"]')
    .selectOption(String(ctx.penvProductionSiteId as number));
  await production.locator('button:has-text("紐付ける")').click();
});

When('ダッシュボードを再読み込みする', async ({ page }) => {
  await page.reload();
  await expect(widget(page)).toBeVisible({ timeout: 30_000 });
});

When('プロジェクト詳細の概要タブを開く', async ({ page, ctx }) => {
  await page.goto(`/projects/${ctx.penvProjectId as number}`);
});

Then(/^環境設定ウィジェットの(テスト|本番)環境に紐付いたサイトのキーが表示される$/, async ({ page, ctx }, label: string) => {
  const key = (label === 'テスト' ? ctx.penvTestSiteKey : ctx.penvProductionSiteKey) as string;
  await expect(slot(widget(page), ENV_LABEL[label]).getByText(key, { exact: true })).toBeVisible({ timeout: 10_000 });
});

Then(/^環境設定ウィジェットの(ローカル|本番)環境は未設定と表示される$/, async ({ page }, label: string) => {
  await expect(slot(widget(page), ENV_LABEL[label]).getByText('未設定', { exact: true })).toBeVisible();
});

Then('本番環境にサイトが紐付いたことがAPIでわかる', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  await expect
    .poll(async () => {
      const res = await request.get(`/api/projects/${ctx.penvProjectId as number}`, { headers });
      return ((await res.json()) as { productionSite: { id: number } | null }).productionSite?.id;
    })
    .toBe(ctx.penvProductionSiteId);
});

Then('概要タブの本番環境に紐付いたサイトのキーが表示される', async ({ page, ctx }) => {
  const key = ctx.penvProductionSiteKey as string;
  const production = page
    .locator('div.rounded-lg')
    .filter({ has: page.getByRole('heading', { name: '本番環境', exact: true }) })
    .first();
  await expect(production.getByText(key, { exact: true })).toBeVisible({ timeout: 10_000 });
});
