import { Given, Then, When, After } from './fixtures';
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
 * ルート単位の loading UI のステップ定義(issue #1475)。
 *
 * loading UI の目印は `data-testid="route-loading"`。存在しないプロジェクトの id は
 * `GET /api/projects/{id}` が 404 になる十分大きい値を使う。
 */

const LOADING = '[data-testid="route-loading"]';
const MISSING_PROJECT_ID = 2147483000;

Given('loading UI 検証用のプロジェクトがある', async ({ request, ctx }) => {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const project = await createFixtureProject(request, token, 'rl1475');
  ctx.rlProjectId = project.id;
  ctx.rlProjectName = project.name;
});

Given('管理者としてログインしている', async ({ page }) => {
  await loginAsAdmin(page);
});

When('プロジェクト詳細画面を開くための遷移を1回済ませておく', async ({ page, ctx }) => {
  // 1回目は Next.js(devモード)のルートコンパイルを含むので、検証の対象にしない。
  await page.goto(`/projects/${ctx.rlProjectId}`);
});

When('プロジェクト詳細画面のHTMLを取得する', async ({ page, ctx }) => {
  const response = await page.request.get(`/projects/${ctx.rlProjectId}`);
  expect(response.ok(), `HTMLの取得に失敗しました (status=${response.status()})`).toBe(true);
  ctx.rlHtml = await response.text();
});

Then('HTMLにloading UIが含まれる', async ({ ctx }) => {
  expect(ctx.rlHtml as string).toContain('data-testid="route-loading"');
});

Then('loading UIはプロジェクト名を含む最終表示より前に配信されている', async ({ ctx }) => {
  const html = ctx.rlHtml as string;
  const loadingAt = html.indexOf('data-testid="route-loading"');
  const finalAt = html.indexOf(ctx.rlProjectName as string);
  expect(loadingAt, 'loading UI が HTML に無い').toBeGreaterThanOrEqual(0);
  expect(finalAt, 'プロジェクト名が HTML に無い').toBeGreaterThanOrEqual(0);
  expect(loadingAt).toBeLessThan(finalAt);
});

When('loading UIの描画を記録しながらプロジェクト詳細画面を開く', async ({ page, ctx }) => {
  // ページのスクリプトより前に走らせ、loading UI が DOM に現れた瞬間を取りこぼさない。
  await page.addInitScript((selector: string) => {
    const w = window as unknown as { __routeLoadingSeen?: boolean };
    w.__routeLoadingSeen = false;
    const check = () => {
      if (document.querySelector(selector)) w.__routeLoadingSeen = true;
    };
    new MutationObserver(check).observe(document, { childList: true, subtree: true });
    check();
  }, LOADING);
  await page.goto(`/projects/${ctx.rlProjectId}`);
});

Then('記録の中にloading UIが描画された時点がある', async ({ page }) => {
  const seen = await page.evaluate(
    () => (window as unknown as { __routeLoadingSeen?: boolean }).__routeLoadingSeen
  );
  expect(seen, 'loading UI が一度も描画されませんでした').toBe(true);
});

Then('プロジェクト名とメンテナンスタブが表示される', async ({ page, ctx }) => {
  await expect(page.getByRole('heading', { name: ctx.rlProjectName as string })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('button', { name: 'メンテナンス', exact: true })).toBeVisible();
});

Then('loading UIは表示されていない', async ({ page }) => {
  await expect(page.locator(LOADING)).toHaveCount(0, { timeout: 30_000 });
});

When('存在しないプロジェクトの詳細画面を開く', async ({ page }) => {
  await page.goto(`/projects/${MISSING_PROJECT_ID}`);
});

Then('見つからないことを示す表示になる', async ({ page }) => {
  await expect(page.getByText('This page could not be found.')).toBeVisible({ timeout: 30_000 });
});

After({ tags: '@route-loading' }, async ({ ctx, request }) => {
  const projectId = ctx.rlProjectId as number | undefined;
  if (projectId === undefined) return;
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  await deleteFixtureProject(request, token, projectId);
});

// 認可による遷移(issue #1475): redirect() はストリーミング下ではクライアント側遷移になる。
// 認可はプロジェクトの取得より前に判定されるので、id は存在しなくてよい。
const REDIRECT_PROJECT_PATH = '/projects/1';

When('ログインせずにプロジェクト詳細画面を開く', async ({ page }) => {
  await page.goto(REDIRECT_PROJECT_PATH, { waitUntil: 'commit' });
});

When('認可を要するプロジェクト詳細画面を開く', async ({ page }) => {
  await page.goto(REDIRECT_PROJECT_PATH, { waitUntil: 'commit' });
});

Then('プロジェクト詳細画面のURLではなくなる', async ({ page }) => {
  await expect(page).not.toHaveURL(/\/projects\/1(\?|$|\/)/, { timeout: 30_000 });
  await expect(page).toHaveURL(/^https:\/\/localhost\/?(\?.*)?$/, { timeout: 30_000 });
});
