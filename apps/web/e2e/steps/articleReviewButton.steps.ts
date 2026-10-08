import { execFileSync } from 'node:child_process';
import type { Locator, Page } from '@playwright/test';
import { Then, When } from './fixtures';
import { expect } from '../support';

/**
 * 記事レビュー画面の「レビュー」ボタンの受け入れシナリオを支えるステップ定義(issue #1345)。
 *
 * プロジェクト・提出済みの PR・テスト環境のサイトは `articleReviewPublish.steps.ts` の前提ステップが
 * `ctx.plan` / `ctx.reviewFixture` / `ctx.reviewSiteKey` に用意する。ここでは画面の操作と表示の確認だけを担う。
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

When('記事レビュー画面でそのPRの「レビュー」を押す', async ({ ctx, page }) => {
  const row = rowOf(page, ctx);
  await expect(row).toBeVisible({ timeout: 30_000 });
  await row.getByRole('button', { name: 'レビュー', exact: true }).click();
});

Then('記事レビュー画面のそのPRの行にテスト環境の投稿URLが新しいタブで開くリンクとして表示される', async ({ ctx, page }) => {
  const link = rowOf(page, ctx).getByRole('link', { name: /^http/ });
  await expect(link).toBeVisible({ timeout: 180_000 });
  await expect(link).toHaveAttribute('target', '_blank');
  await expect(link).toHaveAttribute('rel', /noopener/);
  ctx.reviewLinkHref = await link.getAttribute('href');
});

Then('そのリンクの先はテスト環境のスラッグ「review-sample」の記事である', async ({ ctx }) => {
  const siteKey = ctx.reviewSiteKey as string;
  const ids = execFileSync(
    'docker',
    ['exec', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`,
      'post', 'list', '--name=review-sample', '--post_type=post', '--post_status=publish', '--format=ids'],
    { encoding: 'utf8', timeout: 120_000 }
  ).trim();
  expect(ids, 'テスト環境にスラッグ「review-sample」の公開記事が1件ある').toMatch(/^\d+$/);
  const url = execFileSync(
    'docker',
    ['exec', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`,
      'post', 'get', ids, '--field=url'],
    { encoding: 'utf8', timeout: 120_000 }
  ).trim();
  expect(ctx.reviewLinkHref).toBe(url);
});

Then(/^記事レビュー画面のそのPRの行にエラーとして「(.+)」を含む理由が表示される$/, async ({ ctx, page }, fragment: string) => {
  const alert = rowOf(page, ctx).getByRole('alert');
  await expect(alert).toBeVisible({ timeout: 180_000 });
  await expect(alert).toContainText(fragment);
});

Then('記事レビュー画面のそのPRの行に投稿URLのリンクは表示されない', async ({ ctx, page }) => {
  await expect(rowOf(page, ctx).getByRole('link', { name: /^http/ })).toHaveCount(0);
});
