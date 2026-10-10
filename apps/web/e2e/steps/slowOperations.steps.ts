import type { APIRequestContext, Page } from '@playwright/test';
import { Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 「遅い操作」画面(issue #1471)のステップ定義。
 *
 * 「管理者としてログインする」「一般ユーザーとしてログインする」「ダッシュボードを開く」
 * 「現時点の操作ログを控えておく」「1画面分の複数のAPI呼び出しが1つのoperationIdにまとまるまで待つ」
 * 「一般ユーザーのアクセストークンを取得する」は既存の共通ステップを再利用する。
 */

const SLOW_PATH = '/operation-logs/slow';
const POLL_TIMEOUT_MS = 60_000;
const POLL_INTERVAL_MS = 2_000;

interface TraceRow {
  operationId: string;
  userId: number | null;
}

interface UserOperationLogPage {
  content: Array<TraceRow & { id: number }>;
}

/**
 * ブラウザのローカル時刻を `datetime-local` の値(YYYY-MM-DDTHH:mm)へ。
 *
 * 画面は期間を管理者の個人タイムゾーンで解釈するので、実行ホストのTZとはずれうる(最大 ±14 時間)。
 * そのため呼び出し側はずれを吸収できる幅(±1日以上)で指定する。
 */
function localValue(page: Page, shiftMinutes: number): Promise<string> {
  return page.evaluate((shift) => {
    const d = new Date(Date.now() + shift * 60_000);
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }, shiftMinutes);
}

/** 表の本体の各行について、`columnIndex` 番目のセルの数値(単位や桁区切りを除く)を返す。 */
async function numericColumn(page: Page, tableTestId: string, columnIndex: number): Promise<number[]> {
  const bodyRows = page.getByTestId(tableTestId).locator('tbody tr');
  // 並べ替えのリンクは画面遷移を伴う。URL が変わった直後は新しい文書の表がまだ描画されておらず、
  // 待たずに読むと行が0件に見える。最初の行が表示されるまで待ってから読む。
  await expect(bodyRows.first(), `${tableTestId} の行が表示されません`).toBeVisible({ timeout: 30_000 });
  const cells = await bodyRows.evaluateAll(
    (rows, index) => rows.map((row) => row.querySelectorAll('td')[index]?.textContent ?? ''),
    columnIndex
  );
  return cells.map((text) => Number(text.replace(/[^\d.]/g, '')));
}

function expectNonIncreasing(values: number[], what: string): void {
  expect(values.length, `${what}の行がありません`).toBeGreaterThan(0);
  for (let i = 1; i < values.length; i += 1) {
    expect(values[i - 1], `${what}が降順ではありません: ${values.join(', ')}`).toBeGreaterThanOrEqual(values[i]);
  }
}

When('管理者が直近の期間を指定して「遅い操作」画面を開く', async ({ page }) => {
  await page.goto(SLOW_PATH);
  await page.locator('input[name="startDate"]').fill(await localValue(page, -2 * 24 * 60));
  await page.locator('input[name="endDate"]').fill(await localValue(page, 2 * 24 * 60));
  await page.getByRole('button', { name: '集計する' }).click();
  await expect(page).toHaveURL(/startDate=/);
});

Then('ルート別一覧と操作別一覧の2つが表示される', async ({ page }) => {
  await expect(page.getByTestId('route-stats-table')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId('operation-stats-table')).toBeVisible();
  await expect(page.getByTestId('route-stats-table').locator('tbody tr').first()).toBeVisible();
  await expect(page.getByTestId('operation-stats-table').locator('tbody tr').first()).toBeVisible();
});

Then('ルート別一覧は既定でp95の降順に並んでいる', async ({ page }) => {
  expectNonIncreasing(await numericColumn(page, 'route-stats-table', 3), 'p95');
});

Then('ルート別一覧の「件数」見出しを選ぶと、件数の降順に並び替わる', async ({ page }) => {
  await page.getByTestId('route-stats-table').getByRole('link', { name: /^件数/ }).click();
  await expect(page).toHaveURL(/routeSort=count/);
  await expect(page).toHaveURL(/routeDir=desc/);
  expectNonIncreasing(await numericColumn(page, 'route-stats-table', 1), '件数');
});

Then('操作別一覧の先ほどの操作の行から、その操作のトレースを表示できる', async ({ ctx, page }) => {
  const operationId = ctx.at15OperationId as string;
  // 期間を広く取るので、既定の合計所要時間順では100件の上限で対象が落ちうる。開始時刻の新しい順に並べて探す。
  await page.getByTestId('operation-stats-table').getByRole('link', { name: /^開始時刻/ }).click();
  await expect(page).toHaveURL(/opSort=startedAt/);
  await expect(page).toHaveURL(/opDir=desc/);
  await page.getByTestId('operation-stats-table').getByRole('link', { name: operationId }).click();
  await expect(page).toHaveURL(/trace=/);
  const trace = page.getByTestId('operation-trace');
  await expect(trace).toBeVisible({ timeout: 30_000 });
  await expect(trace).toContainText(operationId);
  expect(await trace.locator('tbody tr').count(), '1操作の複数のAPI呼び出しが表示されていません').toBeGreaterThanOrEqual(2);
});

Then('操作ログ画面に「遅い操作」への導線は表示されない', async ({ page }) => {
  await page.goto('/operation-logs');
  await expect(page.locator('h1:has-text("操作ログ")')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('link', { name: '遅い操作' })).toHaveCount(0);
});

Then('一般ユーザーが「遅い操作」画面のURLを直接開いても、一覧は表示されない', async ({ page }) => {
  await page.goto(SLOW_PATH);
  await expect(page.getByTestId('route-stats-table')).toHaveCount(0);
  await expect(page.getByTestId('operation-stats-table')).toHaveCount(0);
  expect(new URL(page.url()).pathname, '非adminが遅い操作画面に留まっています').not.toBe(SLOW_PATH);
});

Then('一般ユーザーは2つの集計APIで403を受ける', async ({ ctx, request }) => {
  const headers = { Authorization: `Bearer ${ctx.userToken as string}` };
  const period = 'startDate=2026-01-01T00:00:00&endDate=2026-12-31T00:00:00';
  for (const kind of ['routes', 'operations']) {
    const response = await request.get(`/api/operation-logs/stats/${kind}?${period}`, { headers });
    expect(response.status(), `GET stats/${kind} が非adminに許可されています`).toBe(403);
  }
});

async function ownOperationWithManyCalls(request: APIRequestContext, token: string): Promise<TraceRow | null> {
  const response = await request.get('/api/operation-logs?page=0&size=200&sort=createdAt,desc', {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok()) return null;
  const logs = (await response.json()) as UserOperationLogPage;
  const counts = new Map<string, TraceRow[]>();
  for (const entry of logs.content) {
    counts.set(entry.operationId, [...(counts.get(entry.operationId) ?? []), entry]);
  }
  for (const entries of counts.values()) {
    if (entries.length >= 2) return entries[0];
  }
  return null;
}

When('一般ユーザーの1画面分の複数のAPI呼び出しが1つのoperationIdにまとまるまで待つ', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + POLL_TIMEOUT_MS);
  const token = ctx.userToken as string;
  const deadline = Date.now() + POLL_TIMEOUT_MS;
  for (;;) {
    const found = await ownOperationWithManyCalls(request, token);
    if (found) {
      ctx.at1471UserOperation = found;
      return;
    }
    if (Date.now() >= deadline) {
      throw new Error('一般ユーザーの操作が操作ログに現れませんでした');
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
  }
});

Then('管理者は一般ユーザーのoperationIdでトレースの全行を取得できる', async ({ ctx, request }) => {
  const target = ctx.at1471UserOperation as TraceRow;
  const adminToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const response = await request.get(`/api/operation-logs/${encodeURIComponent(target.operationId)}`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  });
  expect(response.ok(), `管理者のトレース取得が失敗しました (status=${response.status()})`).toBe(true);
  const rows = (await response.json()) as TraceRow[];
  expect(rows.length, '他利用者の操作の全行が返っていません').toBeGreaterThanOrEqual(2);
  expect(rows.every((row) => row.operationId === target.operationId && row.userId === target.userId)).toBe(true);
});

Then('一般ユーザー本人も自分のoperationIdのトレースを取得できる', async ({ ctx, request }) => {
  const target = ctx.at1471UserOperation as TraceRow;
  const response = await request.get(`/api/operation-logs/${encodeURIComponent(target.operationId)}`, {
    headers: { Authorization: `Bearer ${ctx.userToken as string}` },
  });
  expect(response.ok()).toBe(true);
  expect(((await response.json()) as TraceRow[]).length).toBeGreaterThanOrEqual(2);
});
