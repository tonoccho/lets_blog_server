import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 外部Penpotの到達性(issue #1774)の受け入れシナリオを支えるステップ定義。
 * システム設定のpenpot_base_urlを書き換えるシナリオは @destructive で単独実行される。
 * 復元値はPUTより前にctxへ記録し、途中で失敗しても After で戻す。
 */

interface AppSettingStatus {
  key: string;
  value: string | null;
  source: 'DATABASE' | 'ENVIRONMENT' | 'NONE';
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)}` };
}

async function fetchPenpotSetting(request: APIRequestContext): Promise<AppSettingStatus> {
  const response = await request.get('/api/system-settings/app-settings', {
    headers: await adminHeaders(request),
  });
  expect(response.ok(), `アプリ設定の取得に失敗しました (status=${response.status()})`).toBe(true);
  const setting = ((await response.json()) as AppSettingStatus[]).find((s) => s.key === 'penpot_base_url');
  expect(setting, 'penpot_base_urlの設定項目が見つかりません').toBeDefined();
  return setting!;
}

async function putPenpotUrl(request: APIRequestContext, value: string): Promise<number> {
  const response = await request.put('/api/system-settings/app-settings', {
    headers: await adminHeaders(request),
    data: { penpot_base_url: value },
  });
  return response.status();
}

async function rememberOriginal(request: APIRequestContext, ctx: Record<string, unknown>): Promise<void> {
  if (ctx.penpotUrlOriginal !== undefined) return;
  const current = await fetchPenpotSetting(request);
  ctx.penpotUrlOriginal = current.source === 'DATABASE' ? (current.value ?? '') : '';
}

Given('システム全体のPenpotのURLを未設定にする', async ({ ctx, request }) => {
  await rememberOriginal(request, ctx);
  expect([200, 204]).toContain(await putPenpotUrl(request, ''));
});

Given(/^システム全体のPenpotのURLを「(.+)」に設定する$/, async ({ ctx, request }, url: string) => {
  await rememberOriginal(request, ctx);
  expect([200, 204]).toContain(await putPenpotUrl(request, url));
});

After(async ({ ctx, request }) => {
  if (ctx.penpotUrlOriginal === undefined) return;
  await putPenpotUrl(request, ctx.penpotUrlOriginal as string);
});

const PENPOT_LABEL = 'Penpot ベースURL';

function penpotField(page: Page) {
  return page.locator('label').filter({ hasText: PENPOT_LABEL }).locator('input[name="penpot_base_url"]');
}

When(/^システム設定画面でPenpotのURLを「(.+)」で保存する$/, async ({ page }, url: string) => {
  await page.goto('/admin/system-settings', { waitUntil: 'commit' });
  await expect(page.getByRole('heading', { name: 'システム設定' })).toBeVisible({ timeout: 30_000 });
  const field = penpotField(page);
  await expect(field).toBeVisible({ timeout: 30_000 });
  await field.fill(url);
  await page.getByRole('button', { name: 'まとめて保存' }).click();
  await expect(page.getByText('保存しました。')).toBeVisible({ timeout: 30_000 });
});

Then(/^ページを開き直しても、保存したPenpotのURLが「(.+)」と表示される$/, async ({ page }, url: string) => {
  await page.goto('/admin/system-settings', { waitUntil: 'commit' });
  await expect(penpotField(page)).toHaveValue(url, { timeout: 30_000 });
});

When(/^管理者がPenpotのURLを「(.+)」に保存しようとする$/, async ({ ctx, request }, url: string) => {
  ctx.penpotSaveStatus = await putPenpotUrl(request, url);
});

// platform-service は不正な設定値(IllegalArgumentException)を409 CONFLICTで返す(GlobalExceptionHandler)。
Then(/^保存はシステム設定の検証エラー\(409\)で拒否される$/, async ({ ctx }) => {
  expect(ctx.penpotSaveStatus).toBe(409);
});

Then(/^システム全体のPenpotのURLは「(.+)」のままである$/, async ({ request }, url: string) => {
  expect((await fetchPenpotSetting(request)).value).toBe(url);
});

function serviceRow(page: Page, serviceName: string) {
  return page
    .locator('section')
    .filter({ has: page.getByRole('heading', { name: '接続サービスの稼働状況' }) })
    .locator('ul > li')
    .filter({ has: page.getByText(serviceName, { exact: true }) });
}

Then(
  /^連携サービスの状況の「Penpot」の行は警告で「未設定\(システム設定で設定してください\)」と表示される$/,
  async ({ page }) => {
    const row = serviceRow(page, 'Penpot');
    await expect(row).toBeVisible({ timeout: 30_000 });
    await expect(row).toContainText('警告');
    await expect(row).toContainText('未設定(システム設定で設定してください)');
  }
);

Then('連携サービスの状況の「Penpot」の行はエラーで理由が表示される', async ({ page }) => {
  const row = serviceRow(page, 'Penpot');
  await expect(row).toBeVisible({ timeout: 30_000 });
  await expect(row).toContainText('エラー');
  await expect(row).toContainText('到達できません');
});

Then('連携サービスの状況の「ComfyUI」の行はエラー表示ではない', async ({ page }) => {
  const row = serviceRow(page, 'ComfyUI');
  await expect(row).toBeVisible({ timeout: 30_000 });
  await expect(row).not.toContainText('エラー');
});
