import type { Page } from '@playwright/test';
import { Given, When } from './fixtures';
import { expect } from '../support';
import { waitForHydrated } from '../support/responseBudgetFixtures';
import { measureAndRecord, openLocation } from '../support/responseBudgetProject';
import { useBulkProject } from '../support/responseBudgetWp';

/**
 * プロジェクトの一括管理(`BulkManagementPanel` 以下)の Server Action の3秒予算シナリオ
 * (issue #1477、`features/response-budget/server-action-project-bulk.feature`)のステップ定義。
 *
 * 予算対象として残るのは比較の取得(タグ・ポスト/ページ)だけ。同期・編集・削除・反映・インストールなど
 * 実 WordPress へ複数環境を逐次呼ぶ操作は、利用者の判断(2026-10-01)で「非同期ハンドオフ待ち(#1478)」に
 * 再分類された(実測で超過、#1552。`docs/ACCEPTANCE_CRITERIA.md` §10.5)ので、そのシナリオとステップは外した。
 * 実 WordPress(AT-7 の固定の2サイト)を相手にする。
 */

Given('応答時間予算の検証用に、AT-7 のテストとローカルの実 WordPress を紐付けた比較用プロジェクトを使う', async ({ request, ctx }) => {
  await useBulkProject(request, ctx);
});

async function openBulkTab(page: Page, ctx: Record<string, unknown>, tab?: string): Promise<void> {
  await openLocation(page, ctx, 'メンテナンス');
  if (tab === undefined) return;
  const button = page.getByRole('button', { name: tab, exact: true });
  await expect(button).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(button);
  await button.click();
}

/** タブの初回取得(読み込み中の表示)が終わり、比較表が出るまで待つ。 */
async function expectComparisonLoaded(page: Page): Promise<void> {
  await expect(page.getByText('読み込み中…')).toHaveCount(0, { timeout: 30_000 });
  await expect(page.getByText('このタブを開くとデータを取得します。')).toHaveCount(0);
  await expect(page.getByText(/^マスター環境:/).first()).toBeVisible({ timeout: 30_000 });
}

When('一括管理のタグのタブを開いて Server Action の往復を計測する', async ({ page, ctx }) => {
  await openBulkTab(page, ctx);
  const tab = page.getByRole('button', { name: 'タグ', exact: true });
  await expect(tab).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(tab);
  await measureAndRecord(page, ctx, 'タグの環境間比較の取得', async () => {
    await tab.click();
    await expectComparisonLoaded(page);
  });
});

When('一括管理のポスト\\/ページのタブを開いて Server Action の往復を計測する', async ({ page, ctx }) => {
  await openBulkTab(page, ctx);
  const tab = page.getByRole('button', { name: 'ポスト/ページ', exact: true });
  await expect(tab).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(tab);
  await measureAndRecord(page, ctx, 'ポスト/ページの環境間比較とステータス一覧の取得', async () => {
    await tab.click();
    await expect(page.getByText('読み込み中…')).toHaveCount(0, { timeout: 30_000 });
    await expect(page.getByRole('option', { name: 'ポスト', exact: true })).toHaveCount(1, { timeout: 30_000 });
  });
});
