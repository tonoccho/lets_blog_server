import type { Page } from '@playwright/test';
import { Then, When } from './fixtures';
import { expect } from '../support';

/**
 * レビュー待ち Pull Request の一覧画面の受け入れシナリオを支えるステップ定義(issue #1340)。
 *
 * プロジェクトのフィクスチャ(GitHub連携あり/なし/無効トークン)は記事プランの
 * `articlePlan.steps.ts` が `ctx.plan` に用意し、`@plan` の After が後片付けする。
 * ここでは画面を開き、表示を確かめることだけを担う。
 */

const DATETIME = /\d{4}\/\d{1,2}\/\d{1,2} \d{1,2}:\d{2}:\d{2}/;

function projectIdOf(ctx: Record<string, unknown>): number {
  const plan = ctx.plan as { projectId: number } | undefined;
  if (!plan) {
    throw new Error('先にプロジェクトを用意するステップを実行すること');
  }
  return plan.projectId;
}

const heading = (page: Page) => page.getByRole('heading', { name: /記事レビュー$/ });
const rowOf = (page: Page, number: string) => page.getByRole('row', { name: new RegExp(`#${number}\\b`) });

When('記事レビュー画面を開く', async ({ ctx, page }) => {
  await page.goto(`/projects/${projectIdOf(ctx)}/article-review`);
  await expect(heading(page)).toBeVisible({ timeout: 30_000 });
});

When(/^プロジェクトのナビゲーションで「(.+)」を選ぶ$/, async ({ page }, label: string) => {
  await page.getByRole('navigation', { name: 'プロジェクトセクション' }).getByRole('link', { name: label }).click();
});

Then('記事レビュー画面が表示される', async ({ ctx, page }) => {
  await expect(page).toHaveURL(new RegExp(`/projects/${projectIdOf(ctx)}/article-review$`));
  await expect(heading(page)).toBeVisible({ timeout: 30_000 });
});

Then(
  /^レビュー待ち一覧にシードのPR「(\d+)」が番号・タイトル・ブランチ名・作成日時付きで表示される$/,
  async ({ page }, number: string) => {
    const row = rowOf(page, number);
    await expect(row).toBeVisible({ timeout: 30_000 });
    await expect(row).toContainText('E2Eスタブ: 記事サンプル');
    await expect(row).toContainText('article/e2e-sample');
    await expect(row).toContainText(DATETIME);
  }
);

Then(/^レビュー待ち一覧にシードのPR「(\d+)」は表示されない$/, async ({ page }, number: string) => {
  await expect(rowOf(page, number)).toHaveCount(0);
});

Then(/^レビュー待ち一覧のPR「(\d+)」にGitHubのPull Requestページへのリンクがある$/, async ({ page }, number: string) => {
  const link = rowOf(page, number).getByRole('link', { name: /GitHub/ });
  await expect(link).toHaveAttribute('href', new RegExp(`/pull/${number}$`));
  await expect(link).toHaveAttribute('target', '_blank');
  await expect(link).toHaveAttribute('rel', /noopener/);
});

Then('GitHubリポジトリが未設定であると表示される', async ({ page }) => {
  await expect(page.getByText(/GitHub リポジトリが未設定/)).toBeVisible({ timeout: 30_000 });
});

Then('プロジェクト設定への導線が表示される', async ({ ctx, page }) => {
  await expect(page.getByRole('link', { name: /プロジェクト設定/ })).toHaveAttribute(
    'href',
    `/projects/${projectIdOf(ctx)}`
  );
});

Then('レビュー待ちPRの一覧は表示されない', async ({ page }) => {
  await expect(page.getByRole('table')).toHaveCount(0);
});

Then('Pull Requestの取得に失敗したと表示される', async ({ page }) => {
  await expect(page.getByRole('alert').filter({ hasText: 'Pull Request の取得に失敗しました' })).toBeVisible({
    timeout: 30_000,
  });
});

Then(/^「(.+)」とは表示されない$/, async ({ page }, text: string) => {
  await expect(page.getByText(text)).toHaveCount(0);
});
