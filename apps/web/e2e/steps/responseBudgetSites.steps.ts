import type { Page } from '@playwright/test';
import { Given, When } from './fixtures';
import { expect } from '../support';
import { measureServerActionRoundTrip, recordResponseTime } from '../support/responseBudget';
import { adminHeaders, registerCleanup, uniqueSuffix, waitForHydrated } from '../support/responseBudgetFixtures';

/**
 * サイト(`app/sites/actions.ts` と `app/sites/[id]/edit/actions.ts`)の Server Action の3秒予算シナリオ(issue #1477、
 * `features/response-budget/server-action-sites.feature`)のステップ定義。
 */

Given('応答時間予算の検証用のサイトがある', async ({ request, ctx }) => {
  const unique = uniqueSuffix();
  ctx.responseBudgetSiteName = `E2E 1477 budget ${unique}`;
  const response = await request.post('/api/sites', {
    headers: await adminHeaders(request),
    data: {
      name: `E2E 1477 budget ${unique}`,
      siteKey: `e2e-1477-budget-${unique}`,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'SSH',
        baseUrl: 'http://wrong.invalid',
        sshHost: 'wrong.invalid',
        sshUser: 'nouser',
        wpPath: '/nowhere',
        sshPrivateKeyPem:
          '-----BEGIN OPENSSH PRIVATE KEY-----\ne2e-1477-fixture-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----',
      },
    },
  });
  expect(response.ok(), `サイトの登録に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  const id = ((await response.json()) as { id: number }).id;
  ctx.responseBudgetSiteId = id;
  registerCleanup(ctx, async () => {
    await request.delete(`/api/sites/${id}`, { headers: await adminHeaders(request) });
  });
});


When('サイト編集画面で表示名を変更して保存し Server Action の往復を計測する', async ({ page, ctx }) => {
  const siteId = ctx.responseBudgetSiteId as number;
  await page.goto(`/sites/${siteId}/edit`);
  const name = page.locator('input[name="name"]');
  await expect(name).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(name);
  await name.fill(`E2E 1477 budget ${uniqueSuffix()}`);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByText('保存しました。')).toBeVisible({ timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'サイトの更新(Server Action)の往復');
});

async function openSiteRow(page: Page, ctx: Record<string, unknown>) {
  await page.goto('/sites');
  const row = page.locator('tr', { hasText: ctx.responseBudgetSiteName as string });
  await expect(row).toBeVisible({ timeout: 30_000 });
  return row;
}

async function openEditPage(page: Page, ctx: Record<string, unknown>) {
  await page.goto(`/sites/${ctx.responseBudgetSiteId as number}/edit`);
}

When('サイト一覧画面でサイトを登録して Server Action の往復を計測する', async ({ page, request, ctx }) => {
  const unique = uniqueSuffix();
  const siteKey = `e2e-1477-reg-${unique}`;
  registerCleanup(ctx, async () => {
    const headers = await adminHeaders(request);
    const response = await request.get('/api/sites', { headers });
    if (!response.ok()) return;
    const body = (await response.json()) as { id: number; siteKey: string }[] | { content: { id: number; siteKey: string }[] };
    const sites = Array.isArray(body) ? body : body.content;
    for (const site of sites.filter((s) => s.siteKey === siteKey)) {
      await request.delete(`/api/sites/${site.id}`, { headers });
    }
  });
  await page.goto('/sites');
  const form = page.locator('form', { hasText: 'サイトを登録' });
  await expect(form).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(form.locator('input[name="name"]'));
  await form.locator('input[name="name"]').fill(`E2E 1477 budget reg ${unique}`);
  await form.locator('input[name="siteKey"]').fill(siteKey);
  await form.locator('input[name="baseUrl"]').fill('http://wrong.invalid');
  await form.locator('input[name="sshHost"]').fill('wrong.invalid');
  await form.locator('input[name="sshUser"]').fill('nouser');
  await form.locator('input[name="wpPath"]').fill('/nowhere');
  // 保存済みの鍵ペアがあれば既定で「保存済みの鍵ペアを使う」になる。無ければ生成する(計測の前に済ませる)。
  if ((await form.locator('select[name="sshKeyPairId"]').count()) === 0) {
    await form.getByRole('button', { name: 'SSH鍵ペアを生成' }).click();
    await expect(form.locator('textarea')).toBeVisible({ timeout: 30_000 });
  }
  const timing = await measureServerActionRoundTrip(page, async () => {
    await form.getByRole('button', { name: '登録', exact: true }).click();
    await expect(form.getByText('登録しました。')).toBeVisible({ timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'サイトの登録(Server Action)の往復');
});

When('サイト一覧画面でサイト用の SSH 鍵ペアを生成して Server Action の往復を計測する', async ({ page, ctx }) => {
  await page.goto('/sites');
  const form = page.locator('form', { hasText: 'サイトを登録' });
  await expect(form).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(form.locator('input[name="name"]'));
  // 保存済みの鍵ペアがあると生成ボタンは「新しい鍵ペアを生成する」を選んだときだけ現れる。
  const radio = form.getByLabel('新しい鍵ペアを生成する');
  if ((await radio.count()) > 0) await radio.check();
  const timing = await measureServerActionRoundTrip(page, async () => {
    await form.getByRole('button', { name: 'SSH鍵ペアを生成' }).click();
    await expect(form.locator('textarea')).toBeVisible({ timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'サイトの SSH 鍵ペアの生成(Server Action)の往復');
});

When('サイト一覧画面で検証用のサイトを削除して Server Action の往復を計測する', async ({ page, ctx }) => {
  const row = await openSiteRow(page, ctx);
  const button = row.getByRole('button', { name: '削除', exact: true });
  await waitForHydrated(button);
  page.once('dialog', (dialog) => void dialog.accept());
  const timing = await measureServerActionRoundTrip(page, async () => {
    await button.click();
    await expect(page.locator('tr', { hasText: ctx.responseBudgetSiteName as string })).toHaveCount(0, { timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'サイトの削除(Server Action)の往復');
});

When('サイト一覧画面で検証用のサイトの疎通確認を実行して Server Action の往復を計測する', async ({ page, ctx }) => {
  const row = await openSiteRow(page, ctx);
  const button = row.getByRole('button', { name: '疎通確認', exact: true });
  await waitForHydrated(button);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await button.click();
    await expect(row.getByText(/^(SUCCESS|FAILED)$/)).toBeVisible({ timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'サイトの疎通確認(Server Action)の往復');
});

When('サイト編集画面で wp-cli をインストールして Server Action の往復を計測する', async ({ page, ctx }) => {
  await openEditPage(page, ctx);
  const button = page.getByRole('button', { name: 'wp-cliをインストール' });
  await expect(button).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(button);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await button.click();
    await expect(page.getByRole('button', { name: 'インストール中…' })).toHaveCount(0, { timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'wp-cli のインストール(Server Action)の往復');
});

When('サイト編集画面で静的コンテンツを生成して Server Action の往復を計測する', async ({ page, ctx }) => {
  await openEditPage(page, ctx);
  const button = page.getByRole('button', { name: '生成', exact: true }).first();
  await expect(button).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(button);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await button.click();
    await expect(page.getByRole('button', { name: '生成中…' })).toHaveCount(0, { timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, '静的コンテンツの生成(Server Action)の往復');
});

async function measureLetsblogPanel(page: Page, ctx: Record<string, unknown>, testId: string, operation: string) {
  const siteId = ctx.responseBudgetSiteId as number;
  const status = page.getByTestId(testId);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await page.goto(`/sites/${siteId}/edit`);
    await expect(status).toBeVisible({ timeout: 30_000 });
    // 状態は画面の表示時に自動で取得される。往復の完了は measureServerActionRoundTrip が待つ。
    // 表示の確定(確認中が消える)までは待たない: 未同期のサイトでは同期状態の表示が確定しない(#1660)。
  });
  recordResponseTime(ctx, timing.roundTripMs, operation);
}

When('サイト編集画面を開いて letsblog プラグインの状態表示の Server Action の往復を計測する', async ({ page, ctx }) => {
  await measureLetsblogPanel(page, ctx, 'letsblog-plugin-status', 'letsblog プラグインの状態表示(Server Action)の往復');
});

When('サイト編集画面を開いて letsblog の同期状態表示の Server Action の往復を計測する', async ({ page, ctx }) => {
  await measureLetsblogPanel(page, ctx, 'letsblog-sync-status', 'letsblog の同期状態表示(Server Action)の往復');
});
