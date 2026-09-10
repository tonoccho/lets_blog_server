import type { Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, createFixtureProject, deleteFixtureProject, expect, fetchAccessToken, loginAsAdmin } from '../support';

/**
 * プロジェクト詳細画面「AIモデル管理」カード・LLMタブのレビューステップ別設定
 * (issue #1212、#1211のAPIを画面から使う)のステップ定義。
 *
 * `projectManagement.steps.ts`等と同様、ステップ定義ファイルは兄弟issueと相乗りしない方針
 * (CLAUDE.md所定の方針を踏襲したこのリポジトリの慣行)のため、必要なヘルパーはこのファイル
 * 内に閉じて持つ。
 */

async function adminToken(request: import('@playwright/test').APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

Given('レビューステップ設定検証用のプロジェクトがある', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const project = await createFixtureProject(request, token, 'at-1212-review-step');
  ctx.reviewStepProjectId = project.id;
});

async function openLlmTab(page: Page, projectId: number): Promise<void> {
  await loginAsAdmin(page);
  await page.goto(`/projects/${projectId}`, { waitUntil: 'commit' });
  const aiTab = page.getByRole('button', { name: 'AI・アセット', exact: true });
  await expect(aiTab).toBeVisible({ timeout: 30_000 });
  await aiTab.click();
  await expect(page.getByText('レビューステップ別のAIモデル設定')).toBeVisible({ timeout: 30_000 });
}

When('プロジェクト詳細ページのAIモデル管理カードのLLMタブを開く', async ({ ctx, page }) => {
  await openLlmTab(page, ctx.reviewStepProjectId as number);
});

When('画面を再読み込みしてAIモデル管理カードのLLMタブを開く', async ({ ctx, page }) => {
  await openLlmTab(page, ctx.reviewStepProjectId as number);
});

Then(
  /^レビューステップの行が「(.+)」「(.+)」「(.+)」「(.+)」「(.+)」の順で表示される$/,
  async ({ page }, l1: string, l2: string, l3: string, l4: string, l5: string) => {
    const rows = page.locator('table', { has: page.getByText('ステップ') }).locator('tbody tr');
    await expect(rows).toHaveCount(5);
    const labels = await rows.evaluateAll((trs) =>
      trs.map((tr) => tr.querySelector('td')?.textContent ?? '')
    );
    expect(labels).toEqual([l1, l2, l3, l4, l5]);
  }
);

function reviewStepRow(page: Page, label: string) {
  return page.locator('tr', { hasText: label });
}

async function selectedOptionText(page: Page, label: string) {
  return page.evaluate((selectLabel) => {
    const select = document.querySelector(
      `select[aria-label="${selectLabel}"]`
    ) as HTMLSelectElement | null;
    return select?.selectedOptions?.[0]?.textContent ?? null;
  }, label);
}

Then(/^すべてのレビューステップの行のプロバイダーとモデルが「\(プロジェクト既定を使用\)」と表示されている$/, async ({ page }) => {
  for (const stepLabel of ['日本語チェック', '校正チェック', '校閲', '読者視点でのチェック', '文体チェック']) {
    expect(await selectedOptionText(page, `${stepLabel}のプロバイダー`)).toBe('(プロジェクト既定を使用)');
    expect(await selectedOptionText(page, `${stepLabel}のモデル`)).toBe('(プロジェクト既定を使用)');
  }
});

When(
  /^「(.+)」の行でプロバイダーを「(.+)」、モデルを利用可能な候補の1つに選んで保存する$/,
  async ({ ctx, page }, stepLabel: string, provider: string) => {
    const row = reviewStepRow(page, stepLabel);
    await row.getByLabel(`${stepLabel}のプロバイダー`).selectOption(provider);

    const modelSelect = row.getByLabel(`${stepLabel}のモデル`);
    const options = await modelSelect.locator('option').all();
    // 先頭(index 0)は「(プロジェクト既定を使用)」なので、それ以外の最初の候補を選ぶ。
    const modelValue = await options[1].getAttribute('value');
    await modelSelect.selectOption(modelValue ?? '');
    const modelLabel = await options[1].textContent();

    await row.getByRole('button', { name: '保存' }).click();

    ctx.reviewStepSavedProvider = provider;
    ctx.reviewStepSavedModelLabel = (modelLabel ?? '').trim();
  }
);

Then(/^「(.+)」の行の表示が保存した値になる$/, async ({ ctx, page }, stepLabel: string) => {
  await expect
    .poll(async () => selectedOptionText(page, `${stepLabel}のプロバイダー`))
    .toBe(providerLabelOf(ctx.reviewStepSavedProvider as string));
  expect(await selectedOptionText(page, `${stepLabel}のモデル`)).toBe(ctx.reviewStepSavedModelLabel);
});

Then(/^「(.+)」の行の表示が保存した値のままである$/, async ({ ctx, page }, stepLabel: string) => {
  expect(await selectedOptionText(page, `${stepLabel}のプロバイダー`)).toBe(
    providerLabelOf(ctx.reviewStepSavedProvider as string)
  );
  expect(await selectedOptionText(page, `${stepLabel}のモデル`)).toBe(ctx.reviewStepSavedModelLabel);
});

/** ReviewStepSettingsPanel.tsxのPROVIDER_LABELと同じ対応表。 */
function providerLabelOf(provider: string): string {
  const labels: Record<string, string> = {
    OLLAMA: 'Ollama',
    OPENAI: 'OpenAI (ChatGPT)',
    CLAUDE: 'Claude (Anthropic)',
  };
  return labels[provider] ?? provider;
}

After({ tags: '@ai' }, async ({ ctx, request }) => {
  const projectId = ctx.reviewStepProjectId as number | undefined;
  if (projectId !== undefined) {
    const token = await adminToken(request);
    await deleteFixtureProject(request, token, projectId);
  }
});
