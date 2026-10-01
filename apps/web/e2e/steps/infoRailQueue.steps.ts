import { expect, type Page } from '@playwright/test';
import { When, Then } from './fixtures';
import messagesJa from '../../messages/ja.json';
import { findJobIdByMarker } from './aiGenerationJobOwner.steps';

/**
 * 情報表示レールの処理キュー(issue #1407)。
 * 「一般ユーザーとしてログイン済みである」「一般利用者/管理者がAI下書き生成を依頼する」
 * 「ビューポート幅{N}pxでダッシュボードを開く」「左メニューの「…」を選ぶ」「操作ログページが表示される」は
 * 既存のステップを再利用する。
 *
 * 処理キューは既定のタブなので、レールが現れた時点で描画される。サーバ描画のあと
 * ハイドレーションが済んで初めてジョブ一覧の取得が始まるため、各アサーションは
 * 項目の出現を `toBeVisible` の待機で受け止める(クリックを伴わないので再試行は要らない)。
 */

const PANEL_TIMEOUT = 30_000;

const queue = (page: Page) => page.getByTestId('info-rail-queue-panel');

async function jobItem(page: Page, marker: string) {
  const jobId = await findJobIdByMarker(page.request, marker);
  return page.locator(`[data-testid="info-rail-queue-item"][data-job-id="${jobId}"]`);
}

Then('処理キューにそのジョブが種別と開始時刻つきで表示される', async ({ page, ctx }) => {
  const item = await jobItem(page, ctx.ownerJobMarker as string);
  await expect(item).toBeVisible({ timeout: PANEL_TIMEOUT });
  await expect(item).toContainText('llm_draft');
  await expect(item.locator('time')).toHaveAttribute('datetime', /\d{4}-\d{2}-\d{2}T/);
});

Then('処理キューのそのジョブの状態が「完了」になる', async ({ page, ctx }) => {
  const item = await jobItem(page, ctx.ownerJobMarker as string);
  await expect(item).toContainText(messagesJa.infoRail.queueStatusDone, { timeout: PANEL_TIMEOUT });
});

Then('処理キューが読み込まれた後も、そのジョブは処理キューに表示されない', async ({ page, ctx }) => {
  const jobId = await findJobIdByMarker(page.request, ctx.ownerJobMarker as string);
  // 読み込み中の表示が消えるまで待つ(「何も表示されない」を、読み込み前の空振りで通さない)。
  await expect(queue(page)).toBeVisible({ timeout: PANEL_TIMEOUT });
  await expect(queue(page).getByText(messagesJa.infoRail.loading)).toHaveCount(0, { timeout: PANEL_TIMEOUT });
  await expect(
    page.locator(`[data-testid="info-rail-queue-item"][data-job-id="${jobId}"]`)
  ).toHaveCount(0);
});

Then('処理キューに操作ログへのリンクが表示される', async ({ page }) => {
  await expect(queue(page).getByRole('link', { name: messagesJa.infoRail.queueViewLogs })).toBeVisible({
    timeout: PANEL_TIMEOUT,
  });
});

When('処理キューの操作ログへのリンクを選ぶ', async ({ page }) => {
  await queue(page).getByRole('link', { name: messagesJa.infoRail.queueViewLogs }).click();
});
