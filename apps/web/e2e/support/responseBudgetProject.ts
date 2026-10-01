import type { Locator, Page } from '@playwright/test';
import { expect } from './index';
import { measureServerActionRoundTrip, recordResponseTime, type ServerActionTiming } from './responseBudget';
import { waitForHydrated } from './responseBudgetFixtures';

/**
 * プロジェクト詳細(`/projects/[id]`)まわりの Server Action の3秒予算シナリオ(issue #1477)が共有する補助。
 * 計測そのものは `./responseBudget.ts` の共通ヘルパーが担い、ここは**画面を開く・入力する・押す**だけを持つ。
 * ステップを登録しない(プレーンなモジュール)。
 */

export const PROJECT_ID_KEY = 'responseBudgetProjectId';

/** 使い捨てのプロジェクト、無ければ AT-7 の共有の比較用プロジェクト(`responseBudgetWp.ts` の `useBulkProject`)。 */
export function projectId(ctx: Record<string, unknown>): number {
  const id = (ctx[PROJECT_ID_KEY] ?? ctx.responseBudgetBulkProjectId) as number | undefined;
  if (id === undefined) throw new Error('検証用のプロジェクトが用意されていません');
  return id;
}

/** `プロジェクト一覧` / `/settings/adsense`(プロジェクト配下のパス。`/tags#タブ名` でページ内のタブも開く) / タブ名(プロジェクト詳細のタブ)を開く。 */
export async function openLocation(page: Page, ctx: Record<string, unknown>, location: string): Promise<void> {
  if (location === 'プロジェクト一覧') {
    await page.goto('/projects');
    return;
  }
  if (location.startsWith('/')) {
    // `/tags#カスタムタグ管理` のように `#` の後ろを書くと、そのページ内のタブも開く。
    const [path, tabLabel] = location.split('#');
    await page.goto(`/projects/${projectId(ctx)}${path}`);
    if (tabLabel === undefined) return;
    const pageTab = page.getByRole('button', { name: tabLabel, exact: true });
    await expect(pageTab).toBeVisible({ timeout: 30_000 });
    await waitForHydrated(pageTab);
    await pageTab.click();
    return;
  }
  await page.goto(`/projects/${projectId(ctx)}`);
  if (location === '概要') return;
  const tab = page.getByRole('button', { name: location, exact: true });
  await expect(tab).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(tab);
  await tab.click();
}

/** `a=1;b=2` を [['a','1'],['b','2']] にする。値に `;` は使えない。 */
export function parsePairs(spec: string): [string, string][] {
  return spec
    .split(';')
    .filter((part) => part.length > 0)
    .map((part) => {
      const index = part.indexOf('=');
      return [part.slice(0, index), part.slice(index + 1)] as [string, string];
    });
}

export function fieldLocator(page: Page, name: string): Locator {
  return page.locator(`input[name="${name}"], textarea[name="${name}"], select[name="${name}"]`).first();
}

/** その要素を含む `<form>`。 */
export function formOf(target: Locator): Locator {
  return target.locator('xpath=ancestor::form[1]');
}

export async function expectVisibleText(page: Page, expected: string): Promise<void> {
  if (expected === '') return;
  await expect(page.getByText(expected, { exact: true }).first()).toBeVisible({ timeout: 30_000 });
}

export async function measureAndRecord(
  page: Page,
  ctx: Record<string, unknown>,
  operation: string,
  trigger: () => Promise<void>
): Promise<ServerActionTiming> {
  const timing = await measureServerActionRoundTrip(page, trigger);
  recordResponseTime(ctx, timing.roundTripMs, `${operation}(Server Action)の往復`);
  return timing;
}
