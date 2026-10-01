import { Given, When } from './fixtures';
import { expect } from '../support';
import { adminHeaders, registerCleanup, waitForHydrated } from '../support/responseBudgetFixtures';
import { measureAndRecord, openLocation, projectId } from '../support/responseBudgetProject';

/**
 * プロジェクトのカスタムタグ管理(`ProjectCustomTagManager`)の追加・削除の Server Action の3秒予算シナリオ
 * (issue #1477、`features/response-budget/server-action-project-tags.feature`)のステップ定義。
 * 計測は共通の `measureAndRecord`、判定は共通ステップ(`responseBudget.steps.ts`)。
 *
 * 生成フォームも `input[name="tagName"]` を持つので、追加は `form#custom-tag-form` で絞り込む
 * (`customTag.steps.ts` の `upsertForm` と同じ)。
 */

Given('そのプロジェクトのカスタムタグは後片付けの対象にする', async ({ request, ctx }) => {
  const id = projectId(ctx);
  // custom_tags はプロジェクトを消しても残る(ADR-0004)。画面から作られたタグは id を知らないので、一覧から消す。
  registerCleanup(ctx, async () => {
    const headers = await adminHeaders(request);
    const list = await request.get(`/api/projects/${id}/custom-tags`, { headers });
    if (!list.ok()) return;
    for (const tag of (await list.json()) as { id: number }[]) {
      await request.delete(`/api/custom-tags/${tag.id}`, { headers });
    }
  });
});

Given(/^そのプロジェクトにカスタムタグ「([^」]+)」がある$/, async ({ request, ctx }, tagName: string) => {
  const response = await request.post('/api/custom-tags', {
    headers: await adminHeaders(request),
    data: {
      tagName,
      htmlTemplate: '<div class="e2e1477">{{content}}</div>',
      cssContent: '.e2e1477 { color: rgb(1, 2, 3); }',
      description: 'e2e1477 の検証用タグ',
      tagFormat: 'BLOCK',
      projectId: projectId(ctx),
    },
  });
  expect(response.ok(), `カスタムタグの作成に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
});

When(
  /^カスタムタグ管理で「([^」]+)」のタグを追加して Server Action の往復を計測する$/,
  async ({ page, ctx }, tagName: string) => {
    await openLocation(page, ctx, '/tags#カスタムタグ管理');
    const form = page.locator('form#custom-tag-form');
    const name = form.locator('input[name="tagName"]');
    await expect(name).toBeVisible({ timeout: 30_000 });
    await waitForHydrated(name);
    await name.fill(tagName);
    await form.locator('textarea[name="htmlTemplate"]').fill('<div class="e2e1477">{{content}}</div>');
    await measureAndRecord(page, ctx, 'プロジェクトのカスタムタグの追加', async () => {
      await form.getByRole('button', { name: '追加', exact: true }).click();
      await expect(page.getByText('保存しました。')).toBeVisible({ timeout: 30_000 });
    });
  }
);

When(
  /^カスタムタグ管理で「([^」]+)」のタグを削除して Server Action の往復を計測する$/,
  async ({ page, ctx }, tagName: string) => {
    await openLocation(page, ctx, '/tags#カスタムタグ管理');
    const row = page.locator('tr', { has: page.getByText(`[${tagName}]`, { exact: true }) });
    const button = row.getByRole('button', { name: '削除', exact: true });
    await expect(button).toBeVisible({ timeout: 30_000 });
    await waitForHydrated(button);
    page.once('dialog', (dialog) => void dialog.accept());
    await measureAndRecord(page, ctx, 'プロジェクトのカスタムタグの削除', async () => {
      await button.click();
      await expect(page.getByText(`[${tagName}]`, { exact: true })).toHaveCount(0, { timeout: 30_000 });
    });
  }
);
