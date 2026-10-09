import type { Locator, Page } from '@playwright/test';
import { When, Then } from './fixtures';
import { expect, loginAsAdmin } from '../support';
import {
  SYNC_ACCEPTED_NOTICE,
  fillSyncPanel,
  findSyncJob,
  latestSyncJobId,
  openSyncPanel,
  submitSyncPanel,
  waitForSyncJobDone,
} from '../support/environmentSyncPanel';

/**
 * Web画面の環境間同期の非同期化(issue #1697、`environment-sync-web-job.feature`)のステップ定義。
 * 足場「環境同期検証用のプロジェクトと2つのmanagedサイトがある」は既存のものを使う。同期は実際に走るので、完了待ちの上限を長く取る。
 */

const UI_TIMEOUT_MS = 30_000;
const JOB_TIMEOUT_MS = 240_000;

function queueItem(page: Page, jobId: number): Locator {
  return page.locator(`[data-testid="info-rail-queue-item"][data-job-id="${jobId}"]`);
}

/** ログイン済みにして、環境同期パネルを開いて埋め、送信前の状態にする。 */
async function preparePanel(page: Page, ctx: Record<string, unknown>): Promise<Locator> {
  if (!ctx.esLoggedIn) {
    await loginAsAdmin(page);
    ctx.esLoggedIn = true;
  }
  const projectId = ctx.esProjectId as number;
  const panel = await openSyncPanel(page, projectId);
  await fillSyncPanel(panel, 'db');
  ctx.syncJobBefore = await latestSyncJobId(page.request, projectId);
  ctx.syncPanel = panel;
  return panel;
}

When('環境同期パネルから同期を要求する', async ({ page, ctx }) => {
  const panel = await preparePanel(page, ctx);
  await submitSyncPanel(page, panel);
  // 受理前に離れると送信中の Server Action が中断され、ジョブが作られない。受理後の離脱を確かめるので、受理を待つ。
  await expect(panel.getByText(SYNC_ACCEPTED_NOTICE)).toBeVisible({ timeout: UI_TIMEOUT_MS });
});

When('ダッシュボードへ移動してから、同期を要求したプロジェクトの管理画面へ戻る', async ({ ctx, page }) => {
  await page.goto('/', { waitUntil: 'commit' });
  await page.goto(`/projects/${ctx.esProjectId}`, { waitUntil: 'commit' });
});

Then('同期の要求を受け付けた旨がパネルに示され、同期ボタンは押せる状態のままである', async ({ page }) => {
  await expect(page.getByText(SYNC_ACCEPTED_NOTICE)).toBeVisible({ timeout: UI_TIMEOUT_MS });
  await expect(page.getByRole('button', { name: '同期する', exact: true })).toBeEnabled();
  // 完了を待たないので、同期完了の表示はまだ出ない。
  await expect(page.getByText('同期しました。')).toHaveCount(0);
});

Then('処理キューにその環境間同期のジョブが現れる', async ({ ctx, page, request }) => {
  const job = await findSyncJob(request, ctx.esProjectId as number, ctx.syncJobBefore as number);
  ctx.syncJobId = job.id;
  await expect(queueItem(page, job.id)).toBeVisible({ timeout: UI_TIMEOUT_MS });
  await expect(queueItem(page, job.id)).toContainText('環境間同期');
});

When('処理キューのその環境間同期のジョブが完了するまで待つ', async ({ ctx, page, request }) => {
  const job = await findSyncJob(request, ctx.esProjectId as number, ctx.syncJobBefore as number);
  ctx.syncJobId = job.id;
  await expect(queueItem(page, job.id)).toContainText('完了', { timeout: JOB_TIMEOUT_MS });
  // 画面の表示だけでなく、ジョブ自体が done であることも確かめる(failed なら理由つきで落ちる)。
  await waitForSyncJobDone(request, job.id);
});

When('処理キューのその環境間同期のジョブの「結果を見る」を押す', async ({ ctx, page }) => {
  await queueItem(page, ctx.syncJobId as number)
    .getByRole('link', { name: '結果を見る' })
    .click();
});

Then('そのプロジェクトの設定タブが開く', async ({ ctx, page }) => {
  await expect(page).toHaveURL(new RegExp(`/projects/${ctx.esProjectId}\\?tab=settings`), { timeout: UI_TIMEOUT_MS });
  await expect(page.getByRole('heading', { name: '環境同期' })).toBeVisible({ timeout: UI_TIMEOUT_MS });
});
