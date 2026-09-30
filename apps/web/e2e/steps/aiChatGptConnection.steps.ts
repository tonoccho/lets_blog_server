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
 * プロジェクト詳細画面「AI・アセット」タブのChatGPT接続情報セクション(issue #1506)のステップ定義。
 * 他のai系ステップ定義ファイルと同様、ヘルパーはこのファイル内に閉じて持つ。
 * 実OpenAIへはリクエストを送らない(保存はai-serviceのDBへ暗号化して入れるだけ)。
 */

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

const KEY_LABEL = 'OpenAI APIキー';

Given('ChatGPT接続検証用のプロジェクトがある', async ({ ctx, request }) => {
  const project = await createFixtureProject(request, await adminToken(request), 'at-1506-chatgpt');
  ctx.chatGptProjectId = project.id;
});

Given(/^プロジェクトのChatGPT APIキーが「(.+)」で設定されている$/, async ({ ctx, request }, apiKey: string) => {
  const response = await request.put(`/api/projects/${ctx.chatGptProjectId}/api-keys/openai-api-key`, {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: { apiKey },
  });
  expect(response.ok(), `キーの設定に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
});

function section(page: Page): Locator {
  return page.locator('section', { has: page.getByRole('heading', { name: 'ChatGPTの接続情報' }) });
}

function field(page: Page, term: string): Locator {
  return section(page).locator(`dt:has-text("${term}") + dd`);
}

/** 「AI・アセット」タブを開き、ChatGPTの接続情報が見えるまでクリックを再試行する(ハイドレーション前のクリック取りこぼし対策)。 */
async function openAiTab(page: Page): Promise<void> {
  const aiTab = page.getByRole('button', { name: 'AI・アセット', exact: true });
  await expect(aiTab).toBeVisible({ timeout: 30_000 });
  const marker = page.getByRole('heading', { name: 'ChatGPTの接続情報' });
  await expect(async () => {
    await aiTab.click();
    await expect(marker).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
}

When('ChatGPT接続検証用のプロジェクトのLLMタブを開く', async ({ ctx, page }) => {
  await loginAsAdmin(page);
  await page.goto(`/projects/${ctx.chatGptProjectId}`, { waitUntil: 'commit' });
  await openAiTab(page);
});

When('画面を再読み込みしてChatGPT接続検証用のLLMタブを開く', async ({ page }) => {
  await page.reload({ waitUntil: 'commit' });
  await openAiTab(page);
});

When(/^OpenAI APIキー欄に「(.+)」を入力して接続する$/, async ({ page }, value: string) => {
  await page.getByLabel(KEY_LABEL).fill(value);
  await section(page).getByRole('button', { name: '接続', exact: true }).click();
});

When('OpenAI APIキー欄を空のまま接続する', async ({ page }) => {
  await expect(page.getByLabel(KEY_LABEL)).toHaveValue('');
  await section(page).getByRole('button', { name: '接続', exact: true }).click();
});

When('ChatGPTの接続を解除する', async ({ page }) => {
  // 保存済みの状態が表示されてから解除する(取得前は解除ボタンが無い)。
  const button = section(page).getByRole('button', { name: '接続を解除' });
  await expect(button).toBeVisible({ timeout: 15_000 });
  await button.click();
});

Then(
  /^ChatGPTの接続状態が「(.+)」で設定の出所が「(.+)」と表示される$/,
  async ({ page }, status: string, source: string) => {
    await expect(field(page, '接続状態')).toHaveText(status, { timeout: 30_000 });
    await expect(field(page, '設定の出所')).toHaveText(source);
  }
);

Then(/^ChatGPTの設定の出所が「(.+)」ではなくなる$/, async ({ page }, source: string) => {
  await expect(field(page, '設定の出所')).not.toHaveText(source, { timeout: 15_000 });
  await expect(field(page, '設定の出所')).toHaveText(/^(システム設定|環境変数既定|未設定)$/);
});

Then(/^画面のHTMLソースに「(.+)」が含まれない$/, async ({ page }, secret: string) => {
  expect(await page.content()).not.toContain(secret);
});

Then(/^ai-connectionsの応答に「(.+)」が含まれない$/, async ({ ctx, request }, secret: string) => {
  const response = await request.get(`/api/projects/${ctx.chatGptProjectId}/ai-connections`, {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
  expect(response.ok()).toBe(true);
  expect(await response.text()).not.toContain(secret);
});

Then(
  /^「キーを発行する」リンクの遷移先が「(.+)」で新規タブで開く$/,
  async ({ page }, href: string) => {
    const link = section(page).getByRole('link', { name: 'キーを発行する' });
    await expect(link).toHaveAttribute('href', href);
    await expect(link).toHaveAttribute('target', '_blank');
    await expect(link).toHaveAttribute('rel', /noopener/);
  }
);

Then('APIキーは従量課金でサブスクリプションとは別契約である旨が表示される', async ({ page }) => {
  await expect(
    section(page).getByText('APIキーは従量課金で、ChatGPT のサブスクリプションとは別契約です')
  ).toBeVisible();
});

Then('ChatGPTのAPIキー未入力のエラーが表示される', async ({ page }) => {
  await expect(section(page).getByRole('alert')).toContainText('APIキーを入力してください', { timeout: 15_000 });
});

Then('プロジェクトのChatGPT APIキーは保存されていない', async ({ ctx, request }) => {
  const response = await request.get(`/api/projects/${ctx.chatGptProjectId}/api-keys/openai-api-key`, {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
  expect(response.ok()).toBe(true);
  expect((await response.json()).configured).toBe(false);
});

After({ tags: '@ai' }, async ({ ctx, request }) => {
  const projectId = ctx.chatGptProjectId as number | undefined;
  if (projectId !== undefined) {
    await deleteFixtureProject(request, await adminToken(request), projectId);
  }
});
