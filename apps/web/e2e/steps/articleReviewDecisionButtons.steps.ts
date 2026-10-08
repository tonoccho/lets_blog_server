import { execFileSync } from 'node:child_process';
import type { Locator, Page } from '@playwright/test';
import { Then, When } from './fixtures';
import { expect } from '../support';

/**
 * 記事レビュー画面の「レビュー完了」「記事差し戻し」ボタンの受け入れシナリオを支えるステップ定義(issue #1346)。
 *
 * プロジェクト・提出済みの PR・本番環境のサイトは `articleReviewApprove.steps.ts` /
 * `articleReviewPublish.steps.ts` / `articlePlan.steps.ts` の前提ステップが
 * `ctx.plan` / `ctx.reviewFixture` / `ctx.approveSiteKey` に用意する。
 * ここでは画面の操作と行内の表示の確認だけを担う(API・スタブ・DB の確認は既存のステップを使う)。
 */

const WORDPRESS_CONTAINER = 'lbs-wordpress';

function prNumberOf(ctx: Record<string, unknown>): number {
  const fixture = ctx.reviewFixture as { prNumber: number } | undefined;
  if (!fixture) {
    throw new Error('先に提出済みのPRを用意するステップを実行すること');
  }
  return fixture.prNumber;
}

const rowOf = (page: Page, ctx: Record<string, unknown>): Locator =>
  page.getByRole('row', { name: new RegExp(`#${prNumberOf(ctx)}\\b`) });

const productionLink = (page: Page, ctx: Record<string, unknown>): Locator =>
  rowOf(page, ctx).getByRole('link', { name: /^http/ });

When(/^記事レビュー画面でそのPRの「レビュー完了」を押す$/, async ({ ctx, page }) => {
  const row = rowOf(page, ctx);
  await expect(row).toBeVisible({ timeout: 30_000 });
  await row.getByRole('button', { name: 'レビュー完了', exact: true }).click();
});

When(/^記事レビュー画面のレビュー完了の確認で「(実行する|取りやめる)」を選ぶ$/, async ({ ctx, page }, choice: string) => {
  await rowOf(page, ctx)
    .getByRole('group', { name: 'レビュー完了の確認' })
    .getByRole('button', { name: choice, exact: true })
    .click();
});

When(
  /^記事レビュー画面でそのPRの指摘事項に「(.+)」と入力して「記事差し戻し」を押す$/,
  async ({ ctx, page }, feedback: string) => {
    const row = rowOf(page, ctx);
    await expect(row).toBeVisible({ timeout: 30_000 });
    await row.getByRole('textbox', { name: '指摘事項' }).fill(feedback);
    await row.getByRole('button', { name:'記事差し戻し', exact: true }).click();
  }
);

When('記事レビュー画面でそのPRの指摘事項を空のまま「記事差し戻し」を押す', async ({ ctx, page }) => {
  const row = rowOf(page, ctx);
  await expect(row).toBeVisible({ timeout: 30_000 });
  await row.getByRole('button', { name:'記事差し戻し', exact: true }).click();
});

Then(
 '記事レビュー画面のそのPRの行にレビュー完了の確認が表示され、本番へはfront matterのstatusに従って登録されることが示される',
  async ({ ctx, page }) => {
    const confirm = rowOf(page, ctx).getByRole('group', { name: 'レビュー完了の確認' });
    await expect(confirm).toBeVisible();
    await expect(confirm).toContainText('本番環境へ登録し、Pull Request をマージしてブランチを削除します');
    await expect(confirm).toContainText('front matter');
    await expect(confirm).toContainText('status');
  }
);

Then('記事レビュー画面のそのPRの行にレビュー完了の確認は表示されない', async ({ ctx, page }) => {
  await expect(rowOf(page, ctx).getByRole('group', { name: 'レビュー完了の確認' })).toHaveCount(0);
});

Then('記事レビュー画面のそのPRの行に本番の投稿URLが新しいタブで開くリンクとして表示される', async ({ ctx, page }) => {
  const link = productionLink(page, ctx);
  await expect(link).toBeVisible({ timeout: 180_000 });
  await expect(link).toHaveAttribute('target', '_blank');
  await expect(link).toHaveAttribute('rel', /noopener/);
  ctx.decisionLinkHref = await link.getAttribute('href');
});

Then('そのリンクの先は本番環境のその記事である', async ({ ctx }) => {
  const siteKey = ctx.approveSiteKey as string;
  const url = execFileSync(
    'docker',
    ['exec', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`,
      'post', 'get', ctx.approvePostId as string, '--field=url'],
    { encoding: 'utf8', timeout: 120_000 }
  ).trim();
  expect(ctx.decisionLinkHref).toBe(url);
});

Then('一覧の取り直し後もそのPRの行に本番の投稿URLのリンクが残っている', async ({ ctx, page }) => {
  await page.waitForLoadState('networkidle');
  await expect(productionLink(page, ctx)).toBeVisible();
  await expect(productionLink(page, ctx)).toHaveAttribute('href', ctx.decisionLinkHref as string);
});

Then('記事レビュー画面のそのPRの行に差し戻したことが表示される', async ({ ctx, page }) => {
  await expect(rowOf(page, ctx).getByRole('status')).toContainText('記事を差し戻しました', { timeout: 60_000 });
});

Then('記事レビュー画面のそのPRの行に差し戻したことは表示されない', async ({ ctx, page }) => {
  // エラー表示が出てから確かめる(処理前の「まだ出ていない」で偽陽性にしない)
  await expect(rowOf(page, ctx).getByRole('alert')).toBeVisible({ timeout: 60_000 });
  await expect(rowOf(page, ctx).getByText('記事を差し戻しました')).toHaveCount(0);
});
