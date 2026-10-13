import type { Locator, Page } from '@playwright/test';
import { Then, When } from './fixtures';
import { expect } from '../support';
import {
  ASSET_UPLOAD_ACCEPTED_NOTICE,
  ASSET_UPLOAD_RESULT_TEXT,
  findAssetUploadJob,
  waitForAssetUploadJobDone,
} from '../support/assetUploadJobs';

/**
 * アセット画像の全環境アップロードの非同期化(issue #1764、`image-upload.feature`)の、受理後のステップ定義。
 * 追加の操作そのもの(「パネルのギャラリーからそのアップロード画像を選んでアセットとして追加する」)は
 * `imageUpload.steps.ts` が持つ(操作の前に `ctx.auJobBefore` へ最新のジョブIDを控える)。
 */

const UI_TIMEOUT_MS = 30_000;
const JOB_TIMEOUT_MS = 120_000;
const QUEUE_LABEL = 'アセット画像のアップロード';

function queueItem(page: Page, jobId: number): Locator {
  return page.locator(`[data-testid="info-rail-queue-item"][data-job-id="${jobId}"]`);
}

Then('アセットとしての追加を受け付けた旨がパネルに示され、ボタンは押せる状態のままである', async ({ page }) => {
  await expect(page.getByText(ASSET_UPLOAD_ACCEPTED_NOTICE)).toBeVisible({ timeout: UI_TIMEOUT_MS });
  await expect(
    page.getByRole('button', { name: '選択した画像をアセットとして追加(全環境へアップロード)' })
  ).toBeEnabled();
  // 完了を待たないので、環境ごとの結果はまだ出ない。
  await expect(page.getByText(ASSET_UPLOAD_RESULT_TEXT)).toHaveCount(0);
});

When('ダッシュボードへ移動してから、アセットを追加したプロジェクトの管理画面へ戻る', async ({ ctx, page }) => {
  await page.goto('/', { waitUntil: 'commit' });
  await page.goto(`/projects/${ctx.mediaProjectId}`, { waitUntil: 'commit' });
});

Then('処理キューにそのアセット追加のジョブが現れる', async ({ ctx, page, request }) => {
  const job = await findAssetUploadJob(request, ctx.mediaProjectId as number, ctx.auJobBefore as number);
  ctx.auJobId = job.id;
  await expect(queueItem(page, job.id)).toBeVisible({ timeout: UI_TIMEOUT_MS });
  await expect(queueItem(page, job.id)).toContainText(QUEUE_LABEL);
});

When('処理キューのそのアセット追加のジョブが完了するまで待つ', async ({ ctx, page, request }) => {
  const job = await findAssetUploadJob(request, ctx.mediaProjectId as number, ctx.auJobBefore as number);
  ctx.auJobId = job.id;
  await expect(queueItem(page, job.id)).toContainText('完了', { timeout: JOB_TIMEOUT_MS });
  // 画面の表示だけでなく、ジョブ自体が done であることも確かめる(環境ごとの失敗は logs の FAILED で、ジョブは done)。
  await waitForAssetUploadJobDone(request, job.id);
});

When('処理キューのそのアセット追加のジョブの「結果を見る」を押す', async ({ ctx, page }) => {
  await queueItem(page, ctx.auJobId as number)
    .getByRole('link', { name: '結果を見る' })
    .click();
});

Then('「AI・アセット」タブにアセットとしての追加の結果が表示される', async ({ ctx, page }) => {
  await expect(page).toHaveURL(
    new RegExp(`/projects/${ctx.mediaProjectId}\\?tab=ai-models&assetUploadJob=${ctx.auJobId}`),
    { timeout: UI_TIMEOUT_MS }
  );
  await expect(page.getByText(ASSET_UPLOAD_RESULT_TEXT)).toBeVisible({ timeout: UI_TIMEOUT_MS });
});
