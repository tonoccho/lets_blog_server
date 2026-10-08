import type { APIRequestContext, Locator, Page } from '@playwright/test';
import { After, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { measureServerActionRoundTrip, recordResponseTime } from '../support/responseBudget';
import { waitForHydrated } from '../support/responseBudgetFixtures';

/**
 * Web画面のサイト自動構築の非同期化(issue #1696、`site-provisioning-web-job.feature`)のステップ定義。
 * 「サイト一覧ページを開いている」「ダッシュボードへ…」相当の遷移と、判定「Server Action の往復は「…」ミリ秒以内に
 * 返る」は既存のものを使う。構築(実測で最大240秒)は実際に走るので、完了待ちの上限を長く取る。
 */

const JOB_TYPE = 'site_provisioning';
const UI_TIMEOUT_MS = 30_000;
const JOB_TIMEOUT_MS = 300_000;
const ACCEPTED_NOTICE = /構築を要求しました。処理キューに追加されました/;

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function authHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)}` };
}

function queueItem(page: Page, jobId: number): Locator {
  return page.locator(`[data-testid="info-rail-queue-item"][data-job-id="${jobId}"]`);
}

/** 構築フォームを開いて埋め、送信直前の「構築する」ボタンを返す。 */
async function fillManagedForm(page: Page, ctx: Record<string, unknown>): Promise<Locator> {
  const unique = uniqueSuffix();
  const siteKey = `e2e1696-${unique}`;
  ctx.webJobSiteKey = siteKey;
  ctx.webJobSiteName = `E2E 1696 ${unique}`;
  await page.locator('id=site-creation').scrollIntoViewIfNeeded();
  await page.locator('button:has-text("WordPressを新規構築")').click();
  const nameInput = page.locator('input[name="managedName"]');
  await waitForHydrated(nameInput);
  await nameInput.fill(ctx.webJobSiteName as string);
  await page.locator('input[name="managedSiteKey"]').fill(siteKey);
  await page.locator('input[name="managedTitle"]').fill(ctx.webJobSiteName as string);
  await page
    .locator('input[name="managedAdminUser"]')
    .fill(`e2eadmin${unique}`.replace(/[^a-zA-Z0-9._-]/g, '').slice(0, 30));
  await page.locator('input[name="managedAdminEmail"]').fill(`e2e-${unique}@letsblog.local`);
  await page.locator('input[name="managedAdminPassword"]').fill('E2eProvision#Passw0rd1');
  return page.getByRole('button', { name: '構築する', exact: true });
}

When('サイト一覧画面でサイト自動構築を要求して Server Action の往復を計測する', async ({ page, ctx }) => {
  const submit = await fillManagedForm(page, ctx);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await submit.click();
    await expect(page.getByText(ACCEPTED_NOTICE)).toBeVisible({ timeout: UI_TIMEOUT_MS });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'サイト自動構築(createManagedWordPressSiteAction)の往復');
});

When('サイト一覧画面でサイト自動構築を要求する', async ({ page, ctx }) => {
  const submit = await fillManagedForm(page, ctx);
  await submit.click();
  // 受理前に離れると送信中の Server Action が中断され、ジョブが作られない。受理後の離脱を確かめるので、受理を待つ。
  await expect(page.getByText(ACCEPTED_NOTICE)).toBeVisible({ timeout: UI_TIMEOUT_MS });
});

When('ダッシュボードへ移動してから、サイト一覧へ戻る', async ({ page }) => {
  await page.goto('/', { waitUntil: 'commit' });
  await page.goto('/sites', { waitUntil: 'commit' });
});

Then('構築の要求を受け付けた旨がフォームに示され、構築ボタンは押せる状態のままである', async ({ page }) => {
  await expect(page.getByText(ACCEPTED_NOTICE)).toBeVisible({ timeout: UI_TIMEOUT_MS });
  await expect(page.getByRole('button', { name: '構築する', exact: true })).toBeEnabled();
  // 完了を待たないので、構築完了の表示はまだ出ない。
  await expect(page.getByText('構築しました。')).toHaveCount(0);
});

/** このシナリオが要求したジョブを、リクエスト内容のサイトキーで特定する。受理後に作られるので短く再試行する。 */
async function findJob(request: APIRequestContext, siteKey: string): Promise<{ id: number; status: string; resultPayload: string | null }> {
  const headers = await authHeaders(request);
  const deadline = Date.now() + UI_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const list = await request.get('/api/generation-jobs', { headers });
    expect(list.ok(), `ジョブ一覧の取得に失敗しました (status=${list.status()})`).toBe(true);
    for (const job of ((await list.json()) as { id: number; type: string }[]).filter((j) => j.type === JOB_TYPE)) {
      const detail = await request.get(`/api/generation-jobs/${job.id}`, { headers });
      if (!detail.ok()) continue;
      const body = (await detail.json()) as {
        id: number;
        status: string;
        requestPayload: string | null;
        resultPayload: string | null;
      };
      if (body.requestPayload?.includes(`"${siteKey}"`)) {
        return { id: body.id, status: body.status, resultPayload: body.resultPayload };
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`サイトキー ${siteKey} のサイト自動構築ジョブが見つかりません`);
}

Then('処理キューにそのサイト自動構築のジョブが現れる', async ({ ctx, page, request }) => {
  const job = await findJob(request, ctx.webJobSiteKey as string);
  ctx.webJobId = job.id;
  await expect(queueItem(page, job.id)).toBeVisible({ timeout: UI_TIMEOUT_MS });
  await expect(queueItem(page, job.id)).toContainText('サイト自動構築');
});

When('処理キューのそのサイト自動構築のジョブが完了するまで待つ', async ({ ctx, page, request }) => {
  const job = await findJob(request, ctx.webJobSiteKey as string);
  ctx.webJobId = job.id;
  await expect(queueItem(page, job.id)).toContainText('完了', { timeout: JOB_TIMEOUT_MS });
});

When('処理キューのそのサイト自動構築のジョブの「結果を見る」を押す', async ({ ctx, page }) => {
  await queueItem(page, ctx.webJobId as number)
    .getByRole('link', { name: '結果を見る' })
    .click();
});

Then('作成されたサイトの編集画面が開く', async ({ ctx, page, request }) => {
  const job = await findJob(request, ctx.webJobSiteKey as string);
  const siteId = (JSON.parse(job.resultPayload ?? '{}') as { siteId?: number }).siteId;
  expect(siteId, `ジョブの結果にサイトIDがありません: ${job.resultPayload}`).toBeDefined();
  ctx.webJobSiteId = siteId;
  await expect(page).toHaveURL(new RegExp(`/sites/${siteId}/edit`), { timeout: UI_TIMEOUT_MS });
  await expect(page.getByText(ctx.webJobSiteName as string).first()).toBeVisible({ timeout: UI_TIMEOUT_MS });
});

/** 構築したサイトを(WordPress側の実体ごと)消す。失敗したシナリオでも孤児を残さない。 */
After({ tags: '@site-provisioning-web-job' }, async ({ ctx, request }) => {
  const siteKey = ctx.webJobSiteKey as string | undefined;
  if (!siteKey) return;
  const headers = await authHeaders(request);
  // ジョブが構築中だと終わるまでサイトは現れない。完了(または失敗)まで待ってから消す。
  const deadline = Date.now() + JOB_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const job = await findJob(request, siteKey).catch(() => null);
    if (!job || (job.status !== 'running' && job.status !== 'pending')) break;
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  const list = await request.get('/api/sites', { headers });
  if (!list.ok()) return;
  const body = (await list.json()) as { id: number; siteKey: string }[] | { content: { id: number; siteKey: string }[] };
  const sites = Array.isArray(body) ? body : body.content;
  for (const site of sites.filter((s) => s.siteKey === siteKey)) {
    await request.delete(`/api/sites/${site.id}`, { headers, timeout: 120_000 });
  }
});
