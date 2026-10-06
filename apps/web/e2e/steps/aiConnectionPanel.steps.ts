import type { APIRequestContext, Locator, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  createFixtureProject,
  deleteFixtureProject,
  expect,
  fetchAccessToken,
  loginAsAdmin,
} from '../support';

/**
 * プロジェクト詳細画面「設定」タブの接続情報セクション(issue #1504。#1669 で AI・アセットタブから移した)のステップ定義。
 * 他のai系ステップ定義ファイルと同様、ヘルパーはこのファイル内に閉じて持つ。
 */

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

const INPUT_LABEL = 'Ollamaの接続先URL(このプロジェクトで上書き)';

Given('接続情報パネル検証用のプロジェクトがある', async ({ ctx, request }) => {
  const project = await createFixtureProject(request, await adminToken(request), 'at-1504-connection');
  ctx.connectionPanelProjectId = project.id;
});

Given(/^プロジェクトのOllama接続先が「(.+)」に設定されている$/, async ({ ctx, request }, baseUrl: string) => {
  const response = await request.put(`/api/projects/${ctx.connectionPanelProjectId}/ai-models/connections`, {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: { ollamaBaseUrl: baseUrl },
  });
  expect(response.ok(), `接続先の設定に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
});

function section(page: Page, provider: 'Ollama' | 'ComfyUI'): Locator {
  return page.locator('section', { has: page.getByRole('heading', { name: `${provider}の接続情報` }) });
}

function field(page: Page, provider: 'Ollama' | 'ComfyUI', term: string): Locator {
  return section(page, provider).locator(`dt:has-text("${term}") + dd`);
}

/**
 * 「設定」タブを開き、Ollamaの接続情報が見えるまでクリックを再試行する
 * (ハイドレーション前のクリック取りこぼし対策。aiReviewStepModelSettings.steps.tsと同じ)。
 */
async function openAiTab(page: Page): Promise<void> {
  const aiTab = page.getByRole('button', { name: '設定', exact: true });
  await expect(aiTab).toBeVisible({ timeout: 30_000 });
  const marker = page.getByRole('heading', { name: 'Ollamaの接続情報' });
  await expect(async () => {
    await aiTab.click();
    await expect(marker).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
}

When('接続情報パネル検証用のプロジェクトの設定タブを開く', async ({ ctx, page }) => {
  await loginAsAdmin(page);
  await page.goto(`/projects/${ctx.connectionPanelProjectId}`, { waitUntil: 'commit' });
  await openAiTab(page);
});

When('画面を再読み込みして設定タブを開く', async ({ page }) => {
  await page.reload({ waitUntil: 'commit' });
  await openAiTab(page);
});

When(/^OllamaのURL入力欄に「(.+)」を入力して保存する$/, async ({ page }, value: string) => {
  await page.getByLabel(INPUT_LABEL).fill(value);
  await section(page, 'Ollama').getByRole('button', { name: '保存' }).click();
});

When('OllamaのURL入力欄を空にして保存する', async ({ page }) => {
  const input = page.getByLabel(INPUT_LABEL);
  // 保存済みの値が入力欄へ反映されてから空にする(取得前に空にしても解除の検証にならない)。
  await expect(input).not.toHaveValue('', { timeout: 15_000 });
  await input.fill('');
  await section(page, 'Ollama').getByRole('button', { name: '保存' }).click();
});

async function expectConnectionShown(page: Page, provider: 'Ollama' | 'ComfyUI'): Promise<void> {
  await expect(field(page, provider, '接続先URL')).toHaveText(/^https?:\/\/\S+$/, { timeout: 15_000 });
  await expect(field(page, provider, '設定の出所')).toHaveText(/^(プロジェクト設定|システム設定|未設定)$/);
  await expect(field(page, provider, '利用可否')).toHaveText(/^(利用可能|利用不可|警告)$/, { timeout: 30_000 });
}

Then('Ollamaの接続情報に接続先URLと設定の出所と利用可否が表示される', async ({ page }) => {
  await expectConnectionShown(page, 'Ollama');
});

Then('ComfyUIの接続情報に接続先URLと設定の出所と利用可否が表示される', async ({ page }) => {
  await expectConnectionShown(page, 'ComfyUI');
});

Then(
  /^Ollamaの接続先URLが「(.+)」で設定の出所が「(.+)」と表示される$/,
  async ({ page }, url: string, source: string) => {
    await expect(field(page, 'Ollama', '接続先URL')).toHaveText(url, { timeout: 15_000 });
    await expect(field(page, 'Ollama', '設定の出所')).toHaveText(source);
  }
);

Then(/^Ollamaの設定の出所が「(.+)」と表示される$/, async ({ page }, source: string) => {
  await expect(field(page, 'Ollama', '設定の出所')).toHaveText(source, { timeout: 15_000 });
  await expect(page.getByText('環境変数既定')).toHaveCount(0);
});

Then(/^Ollamaの設定の出所が「(.+)」ではなく、URL入力欄は空である$/, async ({ page }, source: string) => {
  await expect(page.getByText('保存しました。')).toBeVisible({ timeout: 15_000 });
  await expect(field(page, 'Ollama', '設定の出所')).not.toHaveText(source);
  await expect(page.getByLabel(INPUT_LABEL)).toHaveValue('');
});

Then('保存のエラーが表示される', async ({ page }) => {
  await expect(section(page, 'Ollama').getByRole('alert')).toContainText('APIエラー', { timeout: 15_000 });
});

Then(/^OllamaのURL入力欄は「(.+)」のままである$/, async ({ page }, value: string) => {
  await expect(page.getByLabel(INPUT_LABEL)).toHaveValue(value);
});

Then(/^Ollamaの利用可否が「(.+)」で理由が表示される$/, async ({ page }, status: string) => {
  await expect(field(page, 'Ollama', '利用可否')).toHaveText(status, { timeout: 30_000 });
  await expect(section(page, 'Ollama').getByText(/^理由: .+/)).toBeVisible();
});

After({ tags: '@ai' }, async ({ ctx, request }) => {
  const projectId = ctx.connectionPanelProjectId as number | undefined;
  if (projectId !== undefined) {
    await deleteFixtureProject(request, await adminToken(request), projectId);
  }
});
