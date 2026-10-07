import type { Page } from '@playwright/test';
import { Given, When } from './fixtures';
import { expect } from '../support';
import { adminHeaders, uniqueSuffix, waitForHydrated } from '../support/responseBudgetFixtures';
import { measureAndRecord, projectId } from '../support/responseBudgetProject';

/**
 * プロジェクト詳細の「AI・アセット」タブ(`ProjectAiModelsPanel` と `ProjectAssetGenerationPanel`)の
 * Server Action の3秒予算シナリオ(issue #1477、`features/response-budget/server-action-project-ai.feature`)
 * のステップ定義。計測は共通の `measureAndRecord`(= `measureServerActionRoundTrip`)、判定は共通ステップ。
 *
 * タブ・サブタブ・パネルを開くと画面が一覧の取得を自動で送る。その往復は、開く操作(クリック)を
 * 計測の trigger にして測る。
 */

const AI_TAB = 'AI・アセット';

/** プロジェクト詳細を開き、「AI・アセット」タブのボタンがハイドレートされるまで待つ。 */
async function gotoProjectAiTab(page: Page, ctx: Record<string, unknown>) {
  await page.goto(`/projects/${projectId(ctx)}`);
  const tab = page.getByRole('button', { name: AI_TAB, exact: true });
  await expect(tab).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(tab);
  return tab;
}

/** 「AI・アセット」タブを開き、LLM タブの初回取得が済んで操作できるまで待つ(計測しない)。 */
async function openAiTab(page: Page, ctx: Record<string, unknown>): Promise<void> {
  const tab = await gotoProjectAiTab(page, ctx);
  await tab.click();
  await expect(page.getByText('レビューステップ別のAIモデル設定')).toBeVisible({ timeout: 30_000 });
}

/** 「画像生成」サブタブを開き、チェックポイント表が出るまで待つ(計測しない)。 */
async function openImageSubTab(page: Page, ctx: Record<string, unknown>): Promise<void> {
  await openAiTab(page, ctx);
  const sub = page.getByRole('button', { name: '画像生成', exact: true });
  await waitForHydrated(sub);
  await sub.click();
  await expect(page.getByText('選択中のチェックポイント:')).toBeVisible({ timeout: 30_000 });
}

When('プロジェクト詳細画面で「AI・アセット」タブを開いて Server Action の往復を計測する', async ({ page, ctx }) => {
  const tab = await gotoProjectAiTab(page, ctx);
  await measureAndRecord(page, ctx, 'AIモデル管理のLLMタブの初回取得', async () => {
    await tab.click();
    await expect(page.getByText('レビューステップ別のAIモデル設定')).toBeVisible({ timeout: 30_000 });
  });
});

When(
  /^AIモデル管理のLLMプロバイダーを「([^」]+)」に切り替えて Server Action の往復を計測する$/,
  async ({ page, ctx }, provider: string) => {
    await openAiTab(page, ctx);
    const select = page.getByLabel('AIプロバイダー(このプロジェクトの既定)');
    await waitForHydrated(select);
    await measureAndRecord(page, ctx, 'LLMプロバイダーの切り替え', async () => {
      await select.selectOption(provider);
      await expect(page.getByText('保存しました。').first()).toBeVisible({ timeout: 30_000 });
    });
  }
);

When(
  'AIモデル管理のLLMモデルを一覧の先頭の候補にして保存し Server Action の往復を計測する',
  async ({ page, ctx }) => {
    await openAiTab(page, ctx);
    // issue #1674: モデル欄はドロップダウン。先頭の候補を選ぶ(一覧の中身には依存しない)。
    const input = page.getByLabel('LLMのモデル', { exact: true });
    await waitForHydrated(input);
    await input.selectOption({ index: 0 });
    const submit = input.locator('xpath=ancestor::form[1]').getByRole('button', { name: '保存', exact: true });
    await expect(submit).toBeEnabled({ timeout: 30_000 });
    await measureAndRecord(page, ctx, 'LLMモデルの選択', async () => {
      await submit.click();
      await expect(page.getByText('保存しました。').first()).toBeVisible({ timeout: 30_000 });
    });
  }
);

When(
  /^AIモデル管理の「([^」]+)」の行でプロバイダーを「([^」]+)」にして保存し Server Action の往復を計測する$/,
  async ({ page, ctx }, stepLabel: string, provider: string) => {
    await openAiTab(page, ctx);
    const row = page.locator('tr', { hasText: stepLabel });
    const providerSelect = row.getByLabel(`${stepLabel}のプロバイダー`);
    await waitForHydrated(providerSelect);
    await providerSelect.selectOption(provider);
    const modelSelect = row.getByLabel(`${stepLabel}のモデル`);
    // 先頭は「(プロジェクト既定を使用)」。それ以外の最初の候補を選ぶ。
    const modelValue = await modelSelect.locator('option').nth(1).getAttribute('value');
    await modelSelect.selectOption(modelValue ?? '');
    await measureAndRecord(page, ctx, '校正ステップ別のモデル設定の保存', async () => {
      await row.getByRole('button', { name: '保存', exact: true }).click();
      await expect
        .poll(() => providerSelect.evaluate((el) => (el as HTMLSelectElement).value), { timeout: 30_000 })
        .toBe(provider);
    });
  }
);

When('AIモデル管理の「画像生成」サブタブを開いて Server Action の往復を計測する', async ({ page, ctx }) => {
  await openAiTab(page, ctx);
  const sub = page.getByRole('button', { name: '画像生成', exact: true });
  await waitForHydrated(sub);
  await measureAndRecord(page, ctx, 'AIモデル管理の画像生成タブの初回取得', async () => {
    await sub.click();
    await expect(page.getByText('選択中のチェックポイント:')).toBeVisible({ timeout: 30_000 });
  });
});

When(
  /^AIモデル管理の画像生成AIを「([^」]+)」に切り替えて Server Action の往復を計測する$/,
  async ({ page, ctx }, provider: string) => {
    await openImageSubTab(page, ctx);
    const select = page.getByLabel('画像生成AI(このプロジェクトの既定)');
    await waitForHydrated(select);
    await measureAndRecord(page, ctx, '画像生成AIの切り替え', async () => {
      await select.selectOption(provider);
      await expect(page.getByText('保存しました。').first()).toBeVisible({ timeout: 30_000 });
    });
  }
);

When('AIモデル管理の選択中でないチェックポイントに切り替えて Server Action の往復を計測する', async ({ page, ctx }) => {
  await openImageSubTab(page, ctx);
  const button = page.locator('button:not([disabled])', { hasText: /^選択$/ }).first();
  await expect(
    button,
    '選択中でないチェックポイントが一覧にありません(ComfyUI スタブの一覧は2件のはず)'
  ).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(button);
  await measureAndRecord(page, ctx, 'ComfyUIチェックポイントの切り替え', async () => {
    await button.click();
    await expect(page.getByText('切り替えました。')).toBeVisible({ timeout: 30_000 });
  });
});

// ---- アセット画像生成パネル ----

async function gotoAssetPanelButton(page: Page, ctx: Record<string, unknown>) {
  await openAiTab(page, ctx);
  const open = page.getByRole('button', { name: 'アセット画像生成', exact: true });
  await expect(open).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(open);
  return open;
}

/** 画像ギャラリーの小見出しを持つ区画の「開く」ボタン。 */
function galleryToggle(page: Page) {
  return page
    .locator('div', { has: page.getByRole('heading', { name: '画像ギャラリーから選択してアップロード' }) })
    .getByRole('button', { name: '開く', exact: true })
    .first();
}

When('アセット画像生成パネルを開いて Server Action の往復を計測する', async ({ page, ctx }) => {
  const open = await gotoAssetPanelButton(page, ctx);
  await measureAndRecord(page, ctx, 'アセット画像生成パネルの選択肢の取得', async () => {
    await open.click();
    await expect(page.getByText('パラメータ選択肢を読み込んでいます…')).toHaveCount(0, { timeout: 30_000 });
    await expect(page.getByRole('heading', { name: 'アセット画像生成' })).toBeVisible({ timeout: 30_000 });
  });
});

When('アセット画像生成パネルの画像ギャラリーを開いて Server Action の往復を計測する', async ({ page, ctx }) => {
  const open = await gotoAssetPanelButton(page, ctx);
  await open.click();
  const toggle = galleryToggle(page);
  await expect(toggle).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(toggle);
  await measureAndRecord(page, ctx, 'アセット画像生成パネルの画像ギャラリーの取得', async () => {
    await toggle.click();
    await expect(page.getByText('読み込んでいます…')).toHaveCount(0, { timeout: 30_000 });
  });
});

// ---- 画像生成ジョブ(requestProjectImageJobAction / fetchImageJobResultAction。#1623) ----

When('アセット画像生成パネルで画像生成ジョブを依頼して Server Action の往復を計測する', async ({ page, ctx }) => {
  const open = await gotoAssetPanelButton(page, ctx);
  await open.click();
  await expect(page.getByRole('heading', { name: 'アセット画像生成' })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText('パラメータ選択肢を読み込んでいます…')).toHaveCount(0, { timeout: 30_000 });
  const prompt = page.getByPlaceholder('生成したい画像の説明');
  await waitForHydrated(prompt);
  await prompt.fill(`e2e 1623 budget ${uniqueSuffix()}`);
  const generate = page.getByRole('button', { name: '生成', exact: true });
  await expect(generate).toBeEnabled({ timeout: 30_000 });
  // 計るのは受付(ジョブの作成)までの往復。生成そのものは #1404 のキューが担う。
  await measureAndRecord(page, ctx, '画像生成ジョブの依頼', async () => {
    await generate.click();
    await expect(page.getByText(/生成を要求しました。処理キューに追加されました/)).toBeVisible({ timeout: 30_000 });
  });
});

Given('応答時間予算の検証用の完了した画像生成ジョブがある', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const accepted = await request.post('/api/ai/image/jobs', {
    headers,
    data: { prompt: `e2e 1623 budget ${uniqueSuffix()}`, projectId: projectId(ctx) },
    timeout: 30_000,
  });
  expect(accepted.status(), `ジョブの受付に失敗しました: ${await accepted.text()}`).toBe(202);
  const jobId = ((await accepted.json()) as { id: number }).id;
  // 完了(成功または失敗)まで待つ。結果の読み取りは完了後の操作なので、計測の前に済ませておく。
  await expect
    .poll(
      async () => {
        const detail = await request.get(`/api/generation-jobs/${jobId}`, { headers });
        return detail.ok() ? ((await detail.json()) as { status: string }).status : 'unknown';
      },
      { timeout: 180_000, intervals: [1_000] }
    )
    .not.toMatch(/^(pending|running|unknown)$/);
  ctx.responseBudgetImageJobId = jobId;
});

When(
  '処理キューの「結果を見る」と同じ経路でそのジョブの結果を開いて Server Action の往復を計測する',
  async ({ page, ctx }) => {
    const jobId = ctx.responseBudgetImageJobId as number;
    await measureAndRecord(page, ctx, '画像生成ジョブの結果の取得', async () => {
      // 「結果を見る」のリンク先(buildImageGenerationResultHref)。パネルが開き、ジョブの結果を読む。
      await page.goto(`/projects/${projectId(ctx)}?tab=ai-models&imageJob=${jobId}`);
      await expect(page.getByRole('heading', { name: 'アセット画像生成' })).toBeVisible({ timeout: 30_000 });
      await expect(page.getByText('パラメータ選択肢を読み込んでいます…')).toHaveCount(0, { timeout: 30_000 });
      await expect(page.getByText(`ジョブ #${jobId} が生成した`)).toBeVisible({ timeout: 30_000 });
    });
  }
);
