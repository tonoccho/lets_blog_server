import type { Locator, Page } from '@playwright/test';
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

/** ダッシュボードのAI接続状況ウィジェット(issue #1501)のステップ定義。 */

async function adminToken(request: import('@playwright/test').APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

function widget(page: Page): Locator {
  return page.locator('div.rounded-lg').filter({ has: page.locator('h2', { hasText: /^AI接続状況$/ }) }).first();
}

function row(page: Page, name: string): Locator {
  return widget(page).getByRole('listitem', { name, exact: true });
}

Given('AI接続状況ウィジェット検証用のプロジェクトがある', async ({ ctx, request }) => {
  const project = await createFixtureProject(request, await adminToken(request), 'at-1501-ai-widget');
  ctx.aiWidgetProjectId = project.id;
});

Given('AI接続状況検証用のプロジェクトのChatGPT APIキーが設定されている', async ({ ctx, request }) => {
  const response = await request.put(`/api/projects/${ctx.aiWidgetProjectId}/api-keys/openai-api-key`, {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: { apiKey: 'sk-at-1501-widget' },
  });
  expect(response.ok(), `キーの設定に失敗しました (status=${response.status()})`).toBe(true);
});

When('AI接続状況を見るためにダッシュボードを開く', async ({ page, ctx }) => {
  await loginAsAdmin(page);
  await page.goto(`/projects/${ctx.aiWidgetProjectId as number}/dashboard`);
  await expect(widget(page).getByRole('listitem')).toHaveCount(4, { timeout: 30_000 });
});

When('AI接続状況のChatGPTの行のリンクを押す', async ({ page }) => {
  await row(page, 'ChatGPT').getByRole('link').click();
});

Then('AI接続状況ウィジェットにOllama、ComfyUI、ChatGPT、Claudeの4行が表示される', async ({ page }) => {
  for (const name of ['Ollama', 'ComfyUI', 'ChatGPT', 'Claude']) {
    await expect(row(page, name)).toHaveCount(1);
  }
});

Then('4行のどれにも「利用可能」か「利用不可」のバッジが表示される', async ({ page }) => {
  for (const name of ['Ollama', 'ComfyUI', 'ChatGPT', 'Claude']) {
    await expect(row(page, name).getByText(/^利用(可能|不可)$/)).toHaveCount(1);
  }
});

Then('AI接続状況の4行のどれにも「<表示名>の接続設定」の歯車リンクがある', async ({ page, ctx }) => {
  for (const name of ['Ollama', 'ComfyUI', 'ChatGPT', 'Claude']) {
    const link = row(page, name).getByRole('link', { name: `${name}の接続設定`, exact: true });
    await expect(link).toHaveCount(1);
    await expect(link).toHaveAttribute('href', `/projects/${ctx.aiWidgetProjectId as number}?tab=settings`);
  }
});

Then('AI接続状況に「接続を設定」のテキストリンクが無い', async ({ page }) => {
  await expect(widget(page).getByText('接続を設定')).toHaveCount(0);
});

Then('AI接続状況のChatGPTの行は「利用不可」で「ChatGPTの接続設定」の歯車リンクがある', async ({ page, ctx }) => {
  const chatGpt = row(page, 'ChatGPT');
  await expect(chatGpt.getByText('利用不可', { exact: true })).toBeVisible();
  await expect(chatGpt.getByRole('link', { name: 'ChatGPTの接続設定', exact: true })).toHaveAttribute(
    'href',
    `/projects/${ctx.aiWidgetProjectId as number}?tab=settings`,
  );
});

Then('AI接続状況のChatGPTの行は「利用可能」で「ChatGPTの接続設定」の歯車リンクがある', async ({ page, ctx }) => {
  const chatGpt = row(page, 'ChatGPT');
  await expect(chatGpt.getByText('利用可能', { exact: true })).toBeVisible();
  await expect(chatGpt.getByRole('link', { name: 'ChatGPTの接続設定', exact: true })).toHaveAttribute(
    'href',
    `/projects/${ctx.aiWidgetProjectId as number}?tab=settings`,
  );
});

Then('プロジェクト詳細の設定タブが選択されている', async ({ page, ctx }) => {
  await expect(page).toHaveURL(new RegExp(`/projects/${ctx.aiWidgetProjectId as number}\\?tab=settings`));
  await expect(page.getByRole('button', { name: '設定', exact: true })).toHaveAttribute('aria-pressed', 'true');
});

After({ tags: '@ai' }, async ({ ctx, request }) => {
  const projectId = ctx.aiWidgetProjectId as number | undefined;
  if (projectId !== undefined) {
    await deleteFixtureProject(request, await adminToken(request), projectId);
  }
});
