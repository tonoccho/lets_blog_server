import type { APIRequestContext, Page } from '@playwright/test';
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
 * 環境の追加・削除・マスター環境の切り替えとGitHub連携設定のステップ定義
 * (issue #1166 / AT-5-2、親issue #931のシナリオ4・5を引き取る子issue)。
 *
 * `projectManagement.steps.ts`(issue #1165)などと同様、ステップ定義ファイルは兄弟issueと
 * 相乗りしない方針(issue本文参照)のため、必要なヘルパーはこのファイル内に閉じて持つ。
 */

type ProjectDetail = {
  id: number;
  masterEnvironment: 'test' | 'production';
  githubRepository: string | null;
  localSite: { id: number } | null;
  testSite: { id: number; siteKey: string } | null;
  productionSite: { id: number; siteKey: string } | null;
};

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function getProjectDetail(request: APIRequestContext, projectId: number): Promise<ProjectDetail> {
  const headers = await adminHeaders(request);
  const response = await request.get(`/api/projects/${projectId}`, { headers });
  expect(
    response.ok(),
    `プロジェクト詳細の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as ProjectDetail;
}

async function createFixtureSite(
  request: APIRequestContext,
  headers: Record<string, string>,
  prefix: string
): Promise<{ id: number; siteKey: string }> {
  const siteKey = `e2e-at5-2-${prefix}-${uniqueSuffix()}`;
  const response = await request.post('/api/sites', {
    headers,
    data: {
      name: `E2E at5-2 ${prefix} site ${siteKey}`,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'AGENT',
        baseUrl: 'http://wordpress',
        username: 'at5-2-fixture',
      },
    },
  });
  expect(
    response.ok(),
    `フィクスチャのサイト登録に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return { id: ((await response.json()) as { id: number }).id, siteKey };
}

// --------------------------------------------------------------- 背景

Given('環境・GitHub連携検証用のプロジェクトがある', async ({ request, ctx }) => {
  const token = await adminToken(request);
  const project = await createFixtureProject(request, token, 'at5-2-env');
  ctx.penvProjectId = project.id;
});

// --------------------------------------------------------------- 環境の追加・削除・マスター環境(親シナリオ4)

Given('環境紐付け用のサイトが2つある', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const testSite = await createFixtureSite(request, headers, 'test');
  const productionSite = await createFixtureSite(request, headers, 'prod');
  ctx.penvTestSiteId = testSite.id;
  ctx.penvTestSiteKey = testSite.siteKey;
  ctx.penvProductionSiteId = productionSite.id;
  ctx.penvProductionSiteKey = productionSite.siteKey;
});

const ENVIRONMENT_SELECT_LABEL: Record<'test' | 'production', string> = {
  test: 'テスト環境に紐付けるサイト',
  production: '本番環境に紐付けるサイト',
};

const ENVIRONMENT_HEADING: Record<'test' | 'production', string> = {
  test: 'テスト環境',
  production: '本番環境',
};

function environmentSlot(page: Page, environment: 'test' | 'production') {
  return page
    .locator('div.rounded-lg', { has: page.getByRole('heading', { name: ENVIRONMENT_HEADING[environment] }) })
    .first();
}

When('テスト環境にサイトを紐付ける', async ({ page, ctx }) => {
  const projectId = ctx.penvProjectId as number;
  const siteId = ctx.penvTestSiteId as number;

  await loginAsAdmin(page);
  await page.goto(`/projects/${projectId}`);
  await page.locator(`select[aria-label="${ENVIRONMENT_SELECT_LABEL.test}"]`).selectOption(String(siteId));
  await environmentSlot(page, 'test').locator('button:has-text("紐付ける")').click();
});

Then('テスト環境にそのサイトが紐付いたことが画面とAPIの両方でわかる', async ({ page, request, ctx }) => {
  const projectId = ctx.penvProjectId as number;
  const testSiteKey = ctx.penvTestSiteKey as string;

  await expect(environmentSlot(page, 'test').getByText(testSiteKey, { exact: true })).toBeVisible({ timeout: 10000 });

  const project = await getProjectDetail(request, projectId);
  expect(project.testSite?.id).toBe(ctx.penvTestSiteId);
});

When('本番環境にサイトを紐付ける', async ({ page, ctx }) => {
  const siteId = ctx.penvProductionSiteId as number;

  await page
    .locator(`select[aria-label="${ENVIRONMENT_SELECT_LABEL.production}"]`)
    .selectOption(String(siteId));
  await environmentSlot(page, 'production').locator('button:has-text("紐付ける")').click();
});

Then('本番環境にそのサイトが紐付いたことが画面とAPIの両方でわかる', async ({ page, request, ctx }) => {
  const projectId = ctx.penvProjectId as number;
  const productionSiteKey = ctx.penvProductionSiteKey as string;

  await expect(environmentSlot(page, 'production').getByText(productionSiteKey, { exact: true })).toBeVisible({ timeout: 10000 });

  const project = await getProjectDetail(request, projectId);
  expect(project.productionSite?.id).toBe(ctx.penvProductionSiteId);
});

When('マスター環境を本番に切り替える', async ({ page }) => {
  await page.locator('select[name="masterEnvironment"]').selectOption('production');
  const masterForm = page.locator('div.rounded-lg', { has: page.getByRole('heading', { name: 'マスター環境' }) }).first();
  await masterForm.locator('button:has-text("保存")').click();
  await expect(masterForm.getByText('保存しました。')).toBeVisible({ timeout: 10000 });
});

Then('マスター環境が本番になったことをAPIで確認できる', async ({ request, ctx }) => {
  const projectId = ctx.penvProjectId as number;
  const project = await getProjectDetail(request, projectId);
  expect(project.masterEnvironment).toBe('production');
});

When('テスト環境の紐付けを切り離す', async ({ page }) => {
  await environmentSlot(page, 'test').locator('button:has-text("切離し")').click();
});

Then('テスト環境の紐付けが解除されたことが画面とAPIの両方でわかる', async ({ page, request, ctx }) => {
  const projectId = ctx.penvProjectId as number;
  const testSiteKey = ctx.penvTestSiteKey as string;

  await expect(environmentSlot(page, 'test').getByText(testSiteKey, { exact: true })).toBeHidden({ timeout: 10000 });

  const project = await getProjectDetail(request, projectId);
  expect(project.testSite).toBeNull();
});

// --------------------------------------------------------------- GitHub連携(親シナリオ5)

Given('プロジェクト詳細ページの設定タブを開いている', async ({ page, ctx }) => {
  const projectId = ctx.penvProjectId as number;
  await loginAsAdmin(page);
  await page.goto(`/projects/${projectId}`);
  await page.locator('button:has-text("設定")').click();
});

When('GitHubリポジトリをオーナー名とリポジトリ名の形式で設定する', async ({ page, ctx }) => {
  const repository = `e2e-at5-2/repo-${uniqueSuffix()}`;
  ctx.penvGithubRepository = repository;

  const repoForm = page.locator('div.rounded-lg', { has: page.getByRole('heading', { name: 'GitHub リポジトリ設定' }) }).first();
  await repoForm.locator('input[name="githubRepository"]').fill(repository);
  await repoForm.locator('button:has-text("保存")').click();
  await expect(repoForm.getByText('保存しました。')).toBeVisible({ timeout: 10000 });
});

Then('設定したGitHubリポジトリを取得できる', async ({ request, ctx }) => {
  const projectId = ctx.penvProjectId as number;
  const project = await getProjectDetail(request, projectId);
  expect(project.githubRepository).toBe(ctx.penvGithubRepository);
});

function githubTokenField(page: Page) {
  return page
    .locator('div.space-y-2.border-t', { has: page.getByRole('heading', { name: 'GitHub Personal Access Token' }) })
    .first();
}

When('GitHubトークンを設定する', async ({ page, ctx }) => {
  const token = `ghp_e2eat52${uniqueSuffix()}`;
  ctx.penvGithubToken = token;

  const field = githubTokenField(page);
  await field.locator('input[name="githubToken"]').fill(token);
  await field.locator('button:has-text("保存")').click();
  await expect(field.getByText('保存しました。')).toBeVisible({ timeout: 10000 });
});

Then('GitHubトークンの状態APIは設定済みだけを返し本体を含まない', async ({ request, ctx }) => {
  const projectId = ctx.penvProjectId as number;
  const token = ctx.penvGithubToken as string;
  const headers = await adminHeaders(request);

  const response = await request.get(`/api/projects/${projectId}/api-keys/github-token`, { headers });
  expect(
    response.ok(),
    `GitHubトークンの状態取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const bodyText = await response.text();
  const body = JSON.parse(bodyText) as Record<string, unknown>;

  expect(Object.keys(body)).toEqual(['configured']);
  expect(body.configured).toBe(true);
  expect(bodyText.includes(token)).toBe(false);
});

Then('画面のGitHubトークン入力欄はpassword型で空のままである', async ({ page }) => {
  const input = githubTokenField(page).locator('input[name="githubToken"]');
  await expect(input).toHaveAttribute('type', 'password');
  await expect(input).toHaveValue('');
});

When('GitHubトークンを削除する', async ({ page }) => {
  const field = githubTokenField(page);
  page.once('dialog', (dialog) => dialog.accept());
  await field.locator('button:has-text("プロジェクト設定を削除")').click();
  await expect(field.getByText('未設定(フォールバック先の設定を使用)')).toBeVisible({ timeout: 10000 });
});

Then('GitHubトークンの状態APIは未設定を返す', async ({ request, ctx }) => {
  const projectId = ctx.penvProjectId as number;
  const headers = await adminHeaders(request);
  const response = await request.get(`/api/projects/${projectId}/api-keys/github-token`, { headers });
  expect(
    response.ok(),
    `GitHubトークンの状態取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const body = (await response.json()) as { configured: boolean };
  expect(body.configured).toBe(false);
});

// --------------------------------------------------------------- 後片付け

After({ tags: '@project' }, async ({ ctx, request }) => {
  const projectId = ctx.penvProjectId as number | undefined;
  const testSiteId = ctx.penvTestSiteId as number | undefined;
  const productionSiteId = ctx.penvProductionSiteId as number | undefined;
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };

  if (testSiteId !== undefined) {
    await request.delete(`/api/sites/${testSiteId}`, { headers });
  }
  if (productionSiteId !== undefined) {
    await request.delete(`/api/sites/${productionSiteId}`, { headers });
  }
  if (projectId !== undefined) {
    await deleteFixtureProject(request, token, projectId);
  }
});
