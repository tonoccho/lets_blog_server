import { After, Given, Then, When } from '../steps/fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  createFixtureProject,
  deleteFixtureProject,
  expect,
  fetchAccessToken,
  loginAsAdmin,
} from './index';
import {
  RESPONSE_ELAPSED_KEY,
  RESPONSE_OPERATION_KEY,
  assertWithinBudget,
  measureFirstDisplay,
  measureServerActionRoundTrip,
  recordResponseTime,
} from './responseBudget';
import { cleanupPageFixtures, getOrBuildPageFixtures } from './pageInventory';
import { clickUntilVisible } from './retryClick';

/**
 * 応答時間(3秒予算)の共通ステップ(issue #1476)。
 *
 * 画面に依存しない。計測値はシナリオの入れ物(`ctx`)の**共通のキー**へ入れ、
 * カスタムタグ専用のキーには依存しない。各画面のステップ定義は、自分の操作を測ったあと
 * {@link recordResponseTime} で結果を渡し、`ならば` 側はここのステップを使う。
 *
 * 計り方の理由は `./responseBudget.ts` の冒頭を読むこと。
 */

function checkBudget(ctx: Record<string, unknown>, limitMs: string): void {
  const elapsed = ctx[RESPONSE_ELAPSED_KEY] as number | undefined;
  const operation = (ctx[RESPONSE_OPERATION_KEY] as string | undefined) ?? '操作';
  expect(elapsed, '応答時間が計測されていません').toBeDefined();
  console.log(`${operation}の所要時間: ${elapsed}ms`);
  assertWithinBudget(elapsed as number, Number(limitMs), operation);
}

Then(/^すべての応答が「(\d+)」ミリ秒以内に返る$/, async ({ ctx }, limitMs: string) => {
  checkBudget(ctx, limitMs);
});

Then(/^ページロードは「(\d+)」ミリ秒以内に完了する$/, async ({ ctx }, limitMs: string) => {
  checkBudget(ctx, limitMs);
});

Then(/^Server Action の往復は「(\d+)」ミリ秒以内に返る$/, async ({ ctx }, limitMs: string) => {
  checkBudget(ctx, limitMs);
});

// ---- 代表例(features/response-budget) ----

Given('応答時間予算の検証用のプロジェクトがある', async ({ request, ctx }) => {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const project = await createFixtureProject(request, token, 'rb1476');
  ctx.responseBudgetProjectId = project.id;
  ctx.responseBudgetProjectName = project.name;
});

Given('応答時間予算の検証のために管理者としてログインしている', async ({ page }) => {
  await loginAsAdmin(page);
});

/**
 * `{siteId}` / `{userId}` を埋めるための検証用のサイトと一般利用者(issue #1477)。
 * 全ページの巡回(AT-18)が使う {@link getOrBuildPageFixtures} を再利用する。
 * プロジェクトも一緒に作られるが、シナリオ内で1回だけで、After で後始末する。
 */
Given('応答時間予算の検証用のサイトと利用者がある', async ({ request, ctx }) => {
  await getOrBuildPageFixtures(ctx, request);
});

/** `{projectId}` は検証用プロジェクト、`{siteId}` `{userId}` は検証用のサイト・一般利用者の id に置き換える。 */
When(/^ウォームアップ後に「(.+)」を開く$/, async ({ page, ctx }, path: string) => {
  const fixtures = ctx.at18PageFixtures as { siteId: number; userId: number } | undefined;
  const url = path
    .replace('{projectId}', String(ctx.responseBudgetProjectId))
    .replace('{siteId}', String(fixtures?.siteId))
    .replace('{userId}', String(fixtures?.userId));
  recordResponseTime(ctx, await measureFirstDisplay(page, url), `${path} の初回表示`);
});

When('プロジェクト名を保存して Server Action の往復を計測する', async ({ page, ctx }) => {
  const saved = page.getByText('保存しました。', { exact: true });
  // 保存は同じ名前で上書きするだけでべき等。ハイドレーション前の空振りは撃ち直すが、
  // 計測するのは実際に送られた Server Action の往復だけ(再試行時間は含めない)。
  const timing = await measureServerActionRoundTrip(page, () =>
    clickUntilVisible(page.getByRole('button', { name: '名前を保存' }), saved)
  );
  recordResponseTime(ctx, timing.roundTripMs, 'プロジェクト名の保存(Server Action)の往復');
});

Then('保存できたことが画面に表示される', async ({ page }) => {
  await expect(page.getByText('保存しました。', { exact: true })).toBeVisible();
});

After({ tags: '@response-budget' }, async ({ ctx, request }) => {
  const pageFixtures = ctx.at18PageFixtures as Parameters<typeof cleanupPageFixtures>[1] | undefined;
  if (pageFixtures !== undefined) await cleanupPageFixtures(request, pageFixtures);
  const projectId = ctx.responseBudgetProjectId as number | undefined;
  if (projectId === undefined) return;
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  await deleteFixtureProject(request, token, projectId);
});
