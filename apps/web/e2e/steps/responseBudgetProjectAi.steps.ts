import type { Page } from '@playwright/test';
import { When } from './fixtures';
import { expect } from '../support';
import { waitForHydrated } from '../support/responseBudgetFixtures';
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
  /^AIモデル管理のLLMモデル名を「([^」]+)」にして保存し Server Action の往復を計測する$/,
  async ({ page, ctx }, modelName: string) => {
    await openAiTab(page, ctx);
    const input = page.getByPlaceholder('gpt-4o-mini');
    await waitForHydrated(input);
    await input.fill(modelName);
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

/** 生成画像ギャラリーの小見出しを持つ区画の「開く」ボタン。 */
function galleryToggle(page: Page) {
  return page
    .locator('div', { has: page.getByRole('heading', { name: '生成画像ギャラリーから選択してアップロード' }) })
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

When('アセット画像生成パネルの生成画像ギャラリーを開いて Server Action の往復を計測する', async ({ page, ctx }) => {
  const open = await gotoAssetPanelButton(page, ctx);
  await open.click();
  const toggle = galleryToggle(page);
  await expect(toggle).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(toggle);
  await measureAndRecord(page, ctx, 'アセット画像生成パネルの生成画像ギャラリーの取得', async () => {
    await toggle.click();
    await expect(page.getByText('読み込んでいます…')).toHaveCount(0, { timeout: 30_000 });
  });
});
