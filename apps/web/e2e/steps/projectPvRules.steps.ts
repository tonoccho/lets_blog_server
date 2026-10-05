import { execFileSync } from 'node:child_process';
import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * プロジェクト設定画面での PV 達成ルールの設定と、本番サイトのプラグインへの送信(issue #1578)のステップ定義。
 * 「ステップ定義ファイルは相乗りしない」方針のため、フィクスチャのヘルパーはここに閉じて持つ。
 *
 * GA の連携は ga-stub に対して API で行う(認可コードの交換 → プロパティ選択。連携操作そのものの検証は
 * analytics/credentials.feature が受け持つ)。本番サイトの状態の確認と細工は wp-cli(docker exec)で行う。
 */

const WORDPRESS_CONTAINER = 'lbs-wordpress';
const GA_CLIENT_ID = 'at1578-ga.apps.googleusercontent.com';
const GA_AUTHORIZATION_CODE = 'at1231-authorization-code';
const GA_PROPERTY_ID = '987654321';

interface PvSite {
  id: number;
  siteKey: string;
}

type Ctx = Record<string, unknown>;

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

function docker(args: string[]): string {
  return execFileSync('docker', args, { encoding: 'utf8', timeout: 180_000 }).trim();
}

function wpCli(siteKey: string, args: string[]): string {
  return docker(['exec', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`, ...args]);
}

function site(ctx: Ctx): PvSite {
  return ctx.pvSite as PvSite;
}

function projectId(ctx: Ctx): number {
  return ctx.pvProjectId as number;
}

async function buildProjectWithProductionSite(ctx: Ctx, request: APIRequestContext): Promise<void> {
  const headers = await adminHeaders(request);
  const suffix = uniqueSuffix();
  const project = await request.post('/api/projects', {
    headers,
    data: { name: `E2E 1578 ${suffix}`, slug: `e2e1578-${suffix}` },
  });
  expect(project.ok(), `プロジェクトの作成に失敗しました (status=${project.status()}): ${await project.text()}`).toBe(true);
  ctx.pvProjectId = ((await project.json()) as { id: number }).id;

  const siteKey = `e2e1578${suffix}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `E2E 1578 ${suffix}`,
      siteKey,
      title: `E2E 1578 ${suffix}`,
      adminUser: 'e2e1578admin',
      adminEmail: `e2e-1578-${suffix}@letsblog.local`,
      adminPassword: `E2e1578#Pv${suffix}`,
      locale: 'ja',
    },
    timeout: 120_000,
  });
  expect(created.ok(), `managedサイトの作成に失敗しました (status=${created.status()}): ${await created.text()}`).toBe(true);
  ctx.pvSite = { id: ((await created.json()) as { id: number }).id, siteKey };

  const bound = await request.post(`/api/projects/${projectId(ctx)}/environments`, {
    headers,
    data: { environment: 'production', siteId: site(ctx).id },
  });
  expect(bound.ok(), `本番環境への紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`).toBe(true);
}

/** クライアント保存 → 認可コード交換 → プロパティ選択(プロパティ選択が「GA 連携」の完了で、ここでアプリが本番サイトへ送る)。 */
async function connectGoogleAnalytics(ctx: Ctx, request: APIRequestContext): Promise<void> {
  const headers = await adminHeaders(request);
  const base = `/api/projects/${projectId(ctx)}/api-keys/google-analytics`;
  const client = await request.put(`${base}/client`, {
    headers,
    data: { clientId: GA_CLIENT_ID, clientSecret: `at1578-ga-client-secret-${uniqueSuffix()}` },
  });
  expect(client.ok(), `GAのOAuthクライアント保存に失敗しました (status=${client.status()})`).toBe(true);
  const callback = await request.post(`${base}/oauth-callback`, {
    headers,
    data: { code: GA_AUTHORIZATION_CODE, redirectUri: 'https://localhost/connect/google-analytics/callback' },
  });
  expect(callback.status(), `GAのOAuth連携に失敗しました: ${await callback.text()}`).toBe(204);
  const property = await request.put(`${base}/property`, { headers, data: { propertyId: GA_PROPERTY_ID } });
  expect(property.ok(), `GAのプロパティ選択に失敗しました (status=${property.status()})`).toBe(true);
}

function snsPage(ctx: Ctx): string {
  return `/projects/${projectId(ctx)}/settings/sns`;
}

async function openSnsPage(ctx: Ctx, page: Page): Promise<void> {
  await page.goto(snsPage(ctx));
  await expect(page.getByTestId('pv-rules-section')).toBeVisible({ timeout: 30000 });
}

async function addRuleThroughUi(page: Page, period: string, threshold: string): Promise<void> {
  await page.locator('select[name="period"]').selectOption({ label: period });
  await page.locator('input[name="threshold"]').fill(threshold);
  await page.getByRole('button', { name: 'ルールを追加' }).click();
}

interface PluginRule {
  id: string;
  period: string;
  threshold: number;
}

function pluginRules(siteKey: string): PluginRule[] {
  let raw = '';
  try {
    raw = wpCli(siteKey, ['option', 'get', 'letsblog_pv_rules', '--format=json']);
  } catch {
    return [];
  }
  return raw === '' ? [] : (JSON.parse(raw) as PluginRule[]);
}

Given('PV ルール検証用に本番サイトを持つプロジェクトを構築しておく', async ({ ctx, request }) => {
  await buildProjectWithProductionSite(ctx, request);
});

Given('PV ルール検証のプロジェクトに GA を連携する', async ({ ctx, request }) => {
  await connectGoogleAnalytics(ctx, request);
});

Given('PV ルール検証の本番サイトのプラグインを停止しておく', async ({ ctx }) => {
  wpCli(site(ctx).siteKey, ['plugin', 'deactivate', 'letsblog']);
});

Given(
  'PV ルール検証のプロジェクトにルール期間{string}閾値{string}を追加して本番サイトへ反映済みである',
  async ({ ctx, page }, period: string, threshold: string) => {
    await openSnsPage(ctx, page);
    await addRuleThroughUi(page, period, threshold);
    await expect(page.getByTestId('pv-send-status')).toContainText('送信済み', { timeout: 60000 });
    await expect.poll(() => pluginRules(site(ctx).siteKey).length, { timeout: 60000 }).toBe(1);
  }
);

When('PV ルール検証のプロジェクトで GA の連携を完了する', async ({ ctx, request }) => {
  await connectGoogleAnalytics(ctx, request);
});

When('PV ルール検証のプロジェクトの SNS 告知設定画面を開く', async ({ ctx, page }) => {
  await openSnsPage(ctx, page);
});

When(
  'PV ルール検証のプロジェクトの SNS 告知設定画面で期間{string}閾値{string}のルールを追加する',
  async ({ ctx, page }, period: string, threshold: string) => {
    await openSnsPage(ctx, page);
    await addRuleThroughUi(page, period, threshold);
  }
);

When('PV ルール検証の本番サイトのプラグインを有効にしておく', async ({ ctx }) => {
  wpCli(site(ctx).siteKey, ['plugin', 'activate', 'letsblog']);
});

When('SNS 告知設定画面で PV ルールを再送する', async ({ page }) => {
  await page.getByRole('button', { name: '再送' }).click();
});

When(
  'PV ルール検証のプロジェクトの SNS 告知設定画面で PV ルール{string}を削除する',
  async ({ ctx, page }, label: string) => {
    await openSnsPage(ctx, page);
    const item = page.getByTestId('pv-rule-item').filter({ hasText: label });
    await item.getByRole('button', { name: '削除' }).click();
  }
);

Then('本番サイトの wp letsblog pv status が設定済みで接続済みを返す', async ({ ctx }) => {
  const status = JSON.parse(wpCli(site(ctx).siteKey, ['letsblog', 'pv', 'status'])) as {
    configured: boolean;
    state: string;
    property_id: string | null;
  };
  expect(status.configured).toBe(true);
  expect(status.state).toBe('接続済み');
  expect(status.property_id).toBe(GA_PROPERTY_ID);
});

Then('SNS 告知設定画面の PV ルール一覧に{string}が表示される', async ({ page }, label: string) => {
  await expect(page.getByTestId('pv-rule-item').filter({ hasText: label })).toBeVisible({ timeout: 30000 });
});

Then('SNS 告知設定画面の PV ルール一覧に{string}は表示されない', async ({ page }, label: string) => {
  await expect(page.getByTestId('pv-rule-item').filter({ hasText: label })).toHaveCount(0, { timeout: 30000 });
});

Then(
  '本番サイトのプラグインのルールは期間{string}閾値{int}の1件だけである',
  async ({ ctx }, period: string, threshold: number) => {
    await expect
      .poll(() => pluginRules(site(ctx).siteKey).map((r) => ({ period: r.period, threshold: r.threshold })), {
        timeout: 30000,
      })
      .toEqual([{ period, threshold }]);
  }
);

Then('本番サイトのプラグインにルールは無い', async ({ ctx }) => {
  await expect.poll(() => pluginRules(site(ctx).siteKey).length, { timeout: 30000 }).toBe(0);
});

Then('SNS 告知設定画面の PV ルールの送信状態は{string}と表示される', async ({ page }, text: string) => {
  await expect(page.getByTestId('pv-send-status')).toContainText(text, { timeout: 60000 });
});

Then('SNS 告知設定画面に告知が GA の集計遅れで遅れることがある旨が表示される', async ({ page }) => {
  await expect(page.getByTestId('pv-lag-note')).toContainText('集計', { timeout: 30000 });
});

Then('SNS 告知設定画面の PV ルール追加欄に理由として{string}が表示される', async ({ page }, text: string) => {
  await expect(page.getByTestId('pv-rules-reason')).toContainText(text, { timeout: 30000 });
});

Then('PV ルールを追加するボタンは押せない', async ({ page }) => {
  await expect(page.getByRole('button', { name: 'ルールを追加' })).toBeDisabled();
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  if (ctx.pvProjectId === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  // analytics_credentials は projects への外部キーを持たない(ADR-0004)ため、プロジェクトより先に消す。
  await request.delete(`/api/projects/${ctx.pvProjectId as number}/api-keys/google-analytics`, { headers });
  await request.delete(`/api/projects/${ctx.pvProjectId as number}`, { headers });
  if (ctx.pvSite !== undefined) {
    await request.delete(`/api/sites/${site(ctx).id}`, { headers });
  }
});
