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

/** プロジェクト詳細のタブ再編(issue #1669)のステップ定義。 */

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

function tabButton(page: Page, name: string): Locator {
  return page.getByRole('button', { name, exact: true });
}

function section(page: Page, provider: string): Locator {
  return page.locator('section', { has: page.getByRole('heading', { name: `${provider}の接続情報` }) });
}

const PROVIDERS = ['Ollama', 'ComfyUI', 'ChatGPT', 'Claude'];
const OLLAMA_INPUT_LABEL = 'Ollamaの接続先URL(このプロジェクトで上書き)';

/** タブを開く。ハイドレーション前のクリック取りこぼしに備え、選択状態になるまでクリックを繰り返す(タブを開くのはべき等)。 */
async function selectTab(page: Page, name: string): Promise<void> {
  const tab = tabButton(page, name);
  await expect(tab).toBeVisible({ timeout: 30_000 });
  await expect(async () => {
    await tab.click();
    await expect(tab).toHaveAttribute('aria-pressed', 'true', { timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
}

Given('タブ構成検証用のプロジェクトがある', async ({ ctx, request }) => {
  const project = await createFixtureProject(request, await adminToken(request), 'at-1669-tabs');
  ctx.detailTabsProjectId = project.id;
});

When('タブ構成検証用のプロジェクトの詳細を開く', async ({ ctx, page }) => {
  await loginAsAdmin(page);
  await page.goto(`/projects/${ctx.detailTabsProjectId as number}`, { waitUntil: 'commit' });
});

When(/^タブ構成検証用のプロジェクトの詳細で「(.+)」タブを開く$/, async ({ ctx, page }, name: string) => {
  await loginAsAdmin(page);
  await page.goto(`/projects/${ctx.detailTabsProjectId as number}`, { waitUntil: 'commit' });
  await selectTab(page, name);
});

When(/^タブ構成検証用のプロジェクトの詳細を「(.+)」で開く$/, async ({ ctx, page }, query: string) => {
  await loginAsAdmin(page);
  await page.goto(`/projects/${ctx.detailTabsProjectId as number}${query}`, { waitUntil: 'commit' });
});

When(/^「(.+)」タブを開く$/, async ({ page }, name: string) => {
  await selectTab(page, name);
});

When(/^画面を再読み込みして「(.+)」タブを開く$/, async ({ page }, name: string) => {
  await page.reload({ waitUntil: 'commit' });
  await selectTab(page, name);
});

When(/^設定タブのOllamaの接続先URLに「(.+)」を入力して保存する$/, async ({ page }, value: string) => {
  await page.getByLabel(OLLAMA_INPUT_LABEL).fill(value);
  await section(page, 'Ollama').getByRole('button', { name: '保存' }).click();
});

Then(/^プロジェクト詳細のタブが「(.+)」の順に表示される$/, async ({ page }, order: string) => {
  const strip = page.locator('div.overflow-x-auto').filter({ has: tabButton(page, '概要') });
  await expect(strip.getByRole('button')).toHaveText(order.split(','), { timeout: 30_000 });
});

Then(/^プロジェクト詳細に「(.+)」ボタンは表示されていない$/, async ({ page }, name: string) => {
  await expect(tabButton(page, '概要')).toBeVisible({ timeout: 30_000 });
  await expect(tabButton(page, name)).toHaveCount(0);
});

Then(/^メンテナンスタブに「(.+)」の見出しがある$/, async ({ page }, title: string) => {
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible({ timeout: 30_000 });
});

Then(/^メンテナンスタブに「(.+)」ボタンがある$/, async ({ page }, name: string) => {
  await expect(tabButton(page, name)).toBeVisible({ timeout: 30_000 });
});

Then('設定タブにOllama、ComfyUI、ChatGPT、Claudeの接続情報が表示される', async ({ page }) => {
  for (const provider of PROVIDERS) {
    await expect(section(page, provider)).toBeVisible({ timeout: 30_000 });
  }
});

Then('AI・アセットタブにはどの接続情報も表示されない', async ({ page }) => {
  // AI・アセットタブ自身の内容(アセット画像生成)が出てから、接続情報が無いことを確かめる。
  await expect(page.getByRole('button', { name: 'アセット画像生成', exact: true })).toBeVisible({ timeout: 30_000 });
  for (const provider of PROVIDERS) {
    await expect(page.getByRole('heading', { name: `${provider}の接続情報` })).toHaveCount(0);
  }
});

Then(/^設定タブのOllamaの接続先URLが「(.+)」と表示される$/, async ({ page }, url: string) => {
  await expect(section(page, 'Ollama').locator('dt:has-text("接続先URL") + dd')).toHaveText(url, { timeout: 30_000 });
});

Then(
  /^設定タブから「(.+)」のリンクで「(.+)」の設定ページが開く$/,
  async ({ ctx, page }, linkName: string, heading: string) => {
    const link = page.getByRole('link', { name: linkName, exact: true });
    await expect(link).toBeVisible({ timeout: 30_000 });
    await link.click();
    await expect(page).toHaveURL(new RegExp(`/projects/${ctx.detailTabsProjectId as number}/settings/`), {
      timeout: 30_000,
    });
    await expect(page.getByRole('heading', { level: 1 })).toContainText(heading, { timeout: 30_000 });
  }
);

Then(/^プロジェクト詳細の「(.+)」タブが選択されている$/, async ({ page }, name: string) => {
  await expect(tabButton(page, name)).toHaveAttribute('aria-pressed', 'true', { timeout: 30_000 });
});

After({ tags: '@ai' }, async ({ ctx, request }) => {
  const projectId = ctx.detailTabsProjectId as number | undefined;
  if (projectId !== undefined) {
    await deleteFixtureProject(request, await adminToken(request), projectId);
  }
});
