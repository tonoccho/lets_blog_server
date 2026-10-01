import type { Page } from '@playwright/test';
import { Given, When } from './fixtures';
import { expect } from '../support';
import { registerCleanup, uniqueSuffix, waitForHydrated } from '../support/responseBudgetFixtures';
import { measureAndRecord, openLocation } from '../support/responseBudgetProject';
import { TARGET_SITE_KEY, deleteMedia, importUnreferencedMedia, wpSlug } from '../support/responseBudgetWp';

/**
 * プロジェクトのガベージコレクション(`GarbageCollectionPanel`)の Server Action の3秒予算シナリオ
 * (issue #1477、`features/response-budget/server-action-project-gc.feature`)のステップ定義。
 * 計測は共通の `measureAndRecord`、判定は共通ステップ(`responseBudget.steps.ts`)。
 * プロジェクトと実 WordPress の用意は `useBulkProject`(`responseBudgetBulk.steps.ts` の背景ステップ)。
 */

const MEDIA_TITLE_KEY = 'responseBudgetGcMediaTitle';

Given('ローカルの実 WordPress にどの投稿からも参照されない検証用のメディアがある', async ({ ctx }) => {
  const title = `e2e1477gc${uniqueSuffix()}`;
  const id = importUnreferencedMedia(wpSlug(TARGET_SITE_KEY), title);
  ctx[MEDIA_TITLE_KEY] = title;
  registerCleanup(ctx, async () => deleteMedia(wpSlug(TARGET_SITE_KEY), id));
});

/** ガベージコレクションのタブを開き、環境を「ローカル」にしてスキャンボタンを返す(計測しない)。 */
async function openGcAndSelectLocal(page: Page, ctx: Record<string, unknown>) {
  await openLocation(page, ctx, 'ガベージコレクション');
  const select = page.getByRole('combobox', { name: '環境' });
  await expect(select).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(select);
  await select.selectOption('local');
  const scan = page.getByRole('button', { name: 'スキャン', exact: true });
  await expect(scan).toBeEnabled({ timeout: 30_000 });
  return scan;
}

When('ガベージコレクションでローカル環境をスキャンして Server Action の往復を計測する', async ({ page, ctx }) => {
  const scan = await openGcAndSelectLocal(page, ctx);
  await measureAndRecord(page, ctx, '未参照メディアのスキャン', async () => {
    await scan.click();
    await expect(page.getByText(/^全\d+件中、参照あり/)).toBeVisible({ timeout: 60_000 });
  });
});

When(
  'ガベージコレクションでローカル環境をスキャンして検証用のメディアだけを削除し Server Action の往復を計測する',
  async ({ page, ctx }) => {
    const scan = await openGcAndSelectLocal(page, ctx);
    await scan.click();
    const row = page.locator('tr', { hasText: ctx[MEDIA_TITLE_KEY] as string });
    await expect(row).toBeVisible({ timeout: 60_000 });
    await row.getByRole('checkbox').check();
    const remove = page.getByRole('button', { name: '選択した1件を削除', exact: true });
    await expect(remove).toBeEnabled({ timeout: 30_000 });
    page.once('dialog', (dialog) => void dialog.accept());
    await measureAndRecord(page, ctx, '未参照メディアの削除とジョブの進捗の取得', async () => {
      await remove.click();
      await expect(page.getByText(/^1件削除しました/)).toBeVisible({ timeout: 120_000 });
    });
  }
);
