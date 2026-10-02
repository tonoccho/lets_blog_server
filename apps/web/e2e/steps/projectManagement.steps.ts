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
import { clickUntilDone, withDialogAccepted } from '../support/retryClick';

/**
 * プロジェクトの作成・設定表示・削除のステップ定義(issue #1165 / AT-5-1、
 * 親issue #931のシナリオ1・2・3を引き取る子issue)。
 *
 * `projectMember.steps.ts`(issue #1164)などと同様、ステップ定義ファイルは兄弟issueと
 * 相乗りしない方針(issue本文参照)のため、必要なヘルパーはこのファイル内に閉じて持つ。
 */

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

// --------------------------------------------------------------- 背景

Given('プロジェクト管理検証用のプロジェクトがある', async ({ request, ctx }) => {
  const token = await adminToken(request);
  const project = await createFixtureProject(request, token, 'at5-1-pm');
  ctx.pmgProjectId = project.id;
  ctx.pmgProjectName = project.name;
});

// --------------------------------------------------------------- 作成・一覧・詳細(親シナリオ1)

Given('プロジェクト一覧ページを開いている', async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto('/projects');
});

Then('プロジェクト一覧の見出しと作成フォームが表示されている', async ({ page }) => {
  await expect(page.locator('h1:has-text("プロジェクト")')).toBeVisible();
  await expect(page.locator('table thead')).toBeVisible();

  await page.locator('id=project-form').scrollIntoViewIfNeeded();
  await expect(page.locator('#project-form input[name="name"]')).toBeVisible();
  await expect(page.locator('#project-form input[name="slug"]')).toBeVisible();
});

async function createProjectViaUi(page: Page, name: string, slug: string): Promise<void> {
  await page.locator('id=project-form').scrollIntoViewIfNeeded();
  await page.locator('#project-form input[name="name"]').fill(name);
  await page.locator('#project-form input[name="slug"]').fill(slug);
  await page.locator('#project-form button:has-text("作成")').click();
  await expect(page.getByText('作成しました。')).toBeVisible({ timeout: 10000 });
}

When('新しいプロジェクトを作成する', async ({ page, ctx }) => {
  const unique = uniqueSuffix();
  const name = `E2E at5-1 project ${unique}`;
  const slug = `e2e-at5-1-project-${unique}`;
  await createProjectViaUi(page, name, slug);
  ctx.pmgCreatedProjectName = name;
});

Then('新しく作成したプロジェクトが一覧に表示される', async ({ page, ctx }) => {
  const name = ctx.pmgCreatedProjectName as string;
  await page.reload();
  const row = page.locator(`tbody tr:has-text("${name}")`);
  await expect(row).toBeVisible({ timeout: 10000 });
});

Then('新しく作成したプロジェクトの詳細ページを開ける', async ({ page, ctx }) => {
  const name = ctx.pmgCreatedProjectName as string;
  const row = page.locator(`tbody tr:has-text("${name}")`);
  const detailLink = row.locator('a:has-text("詳細")');
  await expect(detailLink).toBeVisible();
  await detailLink.click();

  await expect(page).toHaveURL(/\/projects\/\d+$/, { timeout: 10000 });
  await expect(page.locator('h1', { hasText: name })).toBeVisible();
});

// --------------------------------------------------------------- 設定の保存・永続化(親シナリオ2、#913の退行検知)

When('画像生成のデフォルトサイズとCSSセレクタ接頭辞を更新する', async ({ page, request, ctx }) => {
  const projectId = ctx.pmgProjectId as number;

  const width = 800;
  const height = 600;
  const cssSelectorPrefix = `e2e-prefix-${uniqueSuffix()}`;
  ctx.pmgUpdatedWidth = width;
  ctx.pmgUpdatedHeight = height;
  ctx.pmgUpdatedCssSelectorPrefix = cssSelectorPrefix;

  // 画像生成のデフォルトサイズは実際に動く画面(ProjectImageGenerationSizeDefaultsForm.tsx、
  // page.tsxの「AI・アセット」タブから描画されている)を操作して更新する。
  await loginAsAdmin(page);
  await page.goto(`/projects/${projectId}`);
  await page.locator('button:has-text("AI・アセット")').click();
  // 「AI・アセット」タブには保存ボタン付きのフォームが複数あるため、見出しでこのフォームの
  // コンテナに絞り込んでから操作する(他フォームの「保存」ボタンを誤って押さないため)。
  const sizeForm = page.locator('div.rounded-lg', {
    has: page.getByRole('heading', { name: '画像生成のデフォルトサイズ' }),
  });
  await sizeForm.locator('input[name="defaultGeneratedImageWidth"]').fill(String(width));
  await sizeForm.locator('input[name="defaultGeneratedImageHeight"]').fill(String(height));
  await sizeForm.locator('button:has-text("保存")').click();
  await expect(sizeForm.getByText('保存しました。')).toBeVisible({ timeout: 10000 });

  // CSSセレクタ接頭辞は更新用のUIフォームがどの画面からも呼ばれていない
  // (`updateProjectCssSelectorPrefixAction`が未使用)ため、これのみAPI直叩きで更新する。
  const headers = await adminHeaders(request);
  const cssResponse = await request.put(`/api/projects/${projectId}/css-selector-prefix`, {
    headers,
    data: { cssSelectorPrefix },
  });
  expect(
    cssResponse.ok(),
    `CSSセレクタ接頭辞の更新に失敗しました (status=${cssResponse.status()}): ${await cssResponse.text()}`
  ).toBe(true);
});

Then('更新した画像生成のデフォルトサイズとCSSセレクタ接頭辞を取得できる', async ({ request, ctx }) => {
  const projectId = ctx.pmgProjectId as number;
  const headers = await adminHeaders(request);

  // #913: PUT /api/projects/{id}/css-selector-prefix の受け口が無く404になっていた
  // (legacy-api解体時にコントローラを作り忘れていた)。GETで読み直して確実に保存されている
  // ことを確認する = 画面の再読込に相当する。
  const imageResponse = await request.get(`/api/projects/${projectId}/image-settings`, { headers });
  expect(
    imageResponse.ok(),
    `画像生成設定の取得に失敗しました (status=${imageResponse.status()}): ${await imageResponse.text()}`
  ).toBe(true);
  const imageSettings = (await imageResponse.json()) as {
    defaultGeneratedImageWidth: number | null;
    defaultGeneratedImageHeight: number | null;
  };
  expect(imageSettings.defaultGeneratedImageWidth).toBe(ctx.pmgUpdatedWidth);
  expect(imageSettings.defaultGeneratedImageHeight).toBe(ctx.pmgUpdatedHeight);

  const contentResponse = await request.get(`/api/projects/${projectId}/content-settings`, { headers });
  expect(
    contentResponse.ok(),
    `CSSセレクタ接頭辞の取得に失敗しました (status=${contentResponse.status()}): ${await contentResponse.text()}`
  ).toBe(true);
  const contentSettings = (await contentResponse.json()) as { cssSelectorPrefix: string | null };
  expect(contentSettings.cssSelectorPrefix).toBe(ctx.pmgUpdatedCssSelectorPrefix);
});

// --------------------------------------------------------------- 削除してもサイトは残る(親シナリオ3)

Given('そのプロジェクトにサイトが紐付いている', async ({ request, ctx }) => {
  const projectId = ctx.pmgProjectId as number;
  const headers = await adminHeaders(request);
  const siteKey = `e2e-at5-1-site-${uniqueSuffix()}`;

  const siteResponse = await request.post('/api/sites', {
    headers,
    data: {
      name: `E2E at5-1 site ${siteKey}`,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'AGENT',
        baseUrl: 'http://wordpress',
        username: 'at5-1-fixture',
        appPassword: 'at5-1 fixture app password',
      },
    },
  });
  expect(
    siteResponse.ok(),
    `フィクスチャのサイト登録に失敗しました (status=${siteResponse.status()}): ${await siteResponse.text()}`
  ).toBe(true);
  const siteId = ((await siteResponse.json()) as { id: number }).id;
  ctx.pmgSiteId = siteId;

  const bindResponse = await request.post(`/api/projects/${projectId}/environments`, {
    headers,
    data: { environment: 'local', siteId },
  });
  expect(
    bindResponse.ok(),
    `サイトのプロジェクトへの紐付けに失敗しました (status=${bindResponse.status()}): ${await bindResponse.text()}`
  ).toBe(true);
});

When('そのプロジェクトを削除する', async ({ page, ctx }) => {
  const projectId = ctx.pmgProjectId as number;

  // DeleteProjectButton.tsx(page.tsxから描画されている実際のUI)を操作して削除する。
  // window.confirm()の確認ダイアログを承認しないと削除は実行されない。
  await loginAsAdmin(page);
  await page.goto(`/projects/${projectId}`);
  await expect(page.locator('button:has-text("プロジェクトを削除")')).toBeVisible();
  // issue #1386: goto直後はハイドレーション未完了でクリックが空振りしうるため、一覧へ遷移するまで
  // クリックし直す。確認ダイアログは再試行のたびに出るので毎回acceptする。
  const projectsListUrl = /\/projects$/;
  await withDialogAccepted(page, () =>
    clickUntilDone(page.locator('button:has-text("プロジェクトを削除")'), {
      isDone: async () => projectsListUrl.test(new URL(page.url()).pathname),
      waitDone: (timeout) => page.waitForURL(projectsListUrl, { timeout }),
    })
  );
  await expect(page).toHaveURL(projectsListUrl, { timeout: 10000 });
  ctx.pmgProjectDeleted = true;
});

Then('そのプロジェクトは一覧から消えている', async ({ request, ctx }) => {
  const projectId = ctx.pmgProjectId as number;
  const headers = await adminHeaders(request);
  const response = await request.get('/api/projects', { headers });
  expect(response.ok(), `プロジェクト一覧の取得に失敗しました (status=${response.status()})`).toBe(true);
  const projects = (await response.json()) as { id: number }[];
  expect(
    projects.some((project) => project.id === projectId),
    `削除したはずのプロジェクト(id=${projectId})が一覧に残っています`
  ).toBe(false);
});

Then('紐付いていたサイトは削除されずに残っている', async ({ request, ctx }) => {
  const siteId = ctx.pmgSiteId as number;
  const headers = await adminHeaders(request);
  const response = await request.get(`/api/sites/${siteId}`, { headers });
  expect(
    response.ok(),
    `プロジェクト削除の巻き添えでサイト(id=${siteId})が消えています (status=${response.status()})`
  ).toBe(true);
});

// --------------------------------------------------------------- 後片付け

After({ tags: '@project' }, async ({ ctx, request }) => {
  const projectId = ctx.pmgProjectId as number | undefined;
  const projectDeleted = ctx.pmgProjectDeleted as boolean | undefined;
  const siteId = ctx.pmgSiteId as number | undefined;
  const createdProjectName = ctx.pmgCreatedProjectName as string | undefined;
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };

  if (siteId !== undefined) {
    await request.delete(`/api/sites/${siteId}`, { headers });
  }
  if (projectId !== undefined && !projectDeleted) {
    await deleteFixtureProject(request, token, projectId);
  }
  if (createdProjectName !== undefined) {
    const response = await request.get('/api/projects', { headers });
    if (response.ok()) {
      const projects = (await response.json()) as { id: number; name: string }[];
      const created = projects.find((project) => project.name === createdProjectName);
      if (created) {
        await deleteFixtureProject(request, token, created.id);
      }
    }
  }
});
