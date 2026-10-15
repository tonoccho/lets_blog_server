import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { GITLAB_STUB_URL, requireGitLabStub } from '../support/stubs';

/**
 * GitLabの接続先と到達性(issue #1796)の受け入れシナリオを支えるステップ定義。
 * システム設定のgitlab_base_urlを書き換え、GitLabスタブへ異常を注入するシナリオは @destructive で単独実行される。
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

async function fetchGitLabSetting(request: APIRequestContext): Promise<AppSettingStatus> {
  const response = await request.get('/api/system-settings/app-settings', {
    headers: await adminHeaders(request),
  });
  expect(response.ok(), `アプリ設定の取得に失敗しました (status=${response.status()})`).toBe(true);
  const setting = ((await response.json()) as AppSettingStatus[]).find((s) => s.key === 'gitlab_base_url');
  expect(setting, 'gitlab_base_urlの設定項目が見つかりません').toBeDefined();
  return setting!;
}

async function putGitLabUrl(request: APIRequestContext, value: string): Promise<number> {
  const response = await request.put('/api/system-settings/app-settings', {
    headers: await adminHeaders(request),
    data: { gitlab_base_url: value },
  });
  return response.status();
}

async function rememberOriginal(request: APIRequestContext, ctx: Record<string, unknown>): Promise<void> {
  if (ctx.gitlabUrlOriginal !== undefined) return;
  const current = await fetchGitLabSetting(request);
  ctx.gitlabUrlOriginal = current.source === 'DATABASE' ? (current.value ?? '') : '';
}

Given('システム全体のGitLabのURLを未設定にする', async ({ ctx, request }) => {
  await rememberOriginal(request, ctx);
  expect([200, 204]).toContain(await putGitLabUrl(request, ''));
});

Given(/^システム全体のGitLabのURLを「(.+)」に設定する$/, async ({ ctx, request }, url: string) => {
  await rememberOriginal(request, ctx);
  expect([200, 204]).toContain(await putGitLabUrl(request, url));
});

Given('GitLabスタブが初期状態である', async ({ ctx }) => {
  await requireGitLabStub();
  ctx.gitlabStubTouched = true;
  await fetch(`${GITLAB_STUB_URL}/__control/reset`, { method: 'POST' });
});

async function forceStub(body: Record<string, number>): Promise<void> {
  const res = await fetch(`${GITLAB_STUB_URL}/__control/force`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  expect(res.status).toBe(200);
}

Given(/^GitLabスタブが「(\d+)」を返し続けるようにする$/, async ({}, status: string) => {
  await forceStub({ status: Number(status) });
});

Given(/^GitLabスタブの応答を「(\d+)」ミリ秒遅らせ続けるようにする$/, async ({}, delayMs: string) => {
  await forceStub({ delayMs: Number(delayMs) });
});

After(async ({ ctx, request }) => {
  if (ctx.gitlabStubTouched) {
    await fetch(`${GITLAB_STUB_URL}/__control/reset`, { method: 'POST' }).catch(() => undefined);
  }
  if (ctx.gitlabUrlOriginal === undefined) return;
  await putGitLabUrl(request, ctx.gitlabUrlOriginal as string);
});

const GITLAB_LABEL = 'GitLab ベースURL';

function gitlabField(page: Page) {
  return page.locator('label').filter({ hasText: GITLAB_LABEL }).locator('input[name="gitlab_base_url"]');
}

When(/^システム設定画面でGitLabのURLを「(.+)」で保存する$/, async ({ page }, url: string) => {
  await page.goto('/admin/system-settings', { waitUntil: 'commit' });
  await expect(page.getByRole('heading', { name: 'システム設定' })).toBeVisible({ timeout: 30_000 });
  const field = gitlabField(page);
  await expect(field).toBeVisible({ timeout: 30_000 });
  await field.fill(url);
  await page.getByRole('button', { name: 'まとめて保存' }).click();
  await expect(page.getByText('保存しました。')).toBeVisible({ timeout: 30_000 });
});

Then(/^ページを開き直しても、保存したGitLabのURLが「(.+)」と表示される$/, async ({ page }, url: string) => {
  await page.goto('/admin/system-settings', { waitUntil: 'commit' });
  await expect(gitlabField(page)).toHaveValue(url, { timeout: 30_000 });
});

function serviceRow(page: Page, serviceName: string) {
  return page
    .locator('section')
    .filter({ has: page.getByRole('heading', { name: '接続サービスの稼働状況' }) })
    .locator('ul > li')
    .filter({ has: page.getByText(serviceName, { exact: true }) });
}

Then('連携サービスの状況の「GitLab」の行は正常と表示される', async ({ page }) => {
  const row = serviceRow(page, 'GitLab');
  await expect(row).toBeVisible({ timeout: 30_000 });
  await expect(row).toContainText('正常');
});

Then(
  /^連携サービスの状況の「GitLab」の行は警告で「未設定\(システム設定で設定してください\)」と表示される$/,
  async ({ page }) => {
    const row = serviceRow(page, 'GitLab');
    await expect(row).toBeVisible({ timeout: 30_000 });
    await expect(row).toContainText('警告');
    await expect(row).toContainText('未設定(システム設定で設定してください)');
  }
);

Then(/^連携サービスの状況の「GitLab」の行はエラーで理由「(.+)」が表示される$/, async ({ page }, reason: string) => {
  const row = serviceRow(page, 'GitLab');
  await expect(row).toBeVisible({ timeout: 30_000 });
  await expect(row).toContainText('エラー');
  await expect(row).toContainText(reason);
});
