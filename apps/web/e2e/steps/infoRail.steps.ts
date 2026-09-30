import { expect, type Page } from '@playwright/test';
import { When, Then } from './fixtures';
import messagesJa from '../../messages/ja.json';
import { clickUntilVisible } from '../support/retryClick';

/**
 * 本文右の情報表示レール(issue #1489)。
 * 「管理者としてログインする」「ビューポート幅{N}pxでダッシュボードを開く」「左メニューの「…」を選ぶ」
 * 「横スクロールが発生しない」「操作ログに記録される操作を1件実行しておく」は既存の共通ステップを再利用する。
 */

const rail = (page: Page) => page.getByTestId('info-rail');
const drawer = (page: Page) => page.getByRole('dialog', { name: messagesJa.infoRail.title });
const tabButton = (page: Page, label: string) => rail(page).getByRole('button', { name: label, exact: true });

Then('情報表示レールが本文より右に表示される', async ({ page }) => {
  await expect(rail(page)).toBeVisible({ timeout: 15_000 });
  const r = (await rail(page).boundingBox())!;
  const main = (await page.locator('main').boundingBox())!;
  expect(r.x).toBeGreaterThanOrEqual(main.x + main.width - 1);
});

Then('情報表示レールに「処理キュー」と「操作ログ」のタブが表示される', async ({ page }) => {
  await expect(tabButton(page, messagesJa.infoRail.queueTab)).toBeVisible({ timeout: 15_000 });
  await expect(tabButton(page, messagesJa.infoRail.logsTab)).toBeVisible();
});

When(/^情報表示レールの「(.+)」タブを選ぶ$/, async ({ page }, label: string) => {
  await expect(rail(page)).toBeVisible({ timeout: 15_000 });
  // ハイドレーション前のクリックは空振りするため、パネルが現れるまで再試行する(#1381)。
  const expected = label === messagesJa.infoRail.logsTab ? page.getByTestId('info-rail-logs-panel') : page.getByTestId('info-rail-queue-panel');
  await clickUntilVisible(tabButton(page, label), expected);
});

Then('情報表示レールに操作ログが10件以内で新しい順に表示される', async ({ page }) => {
  const items = page.getByTestId('info-rail-log-item');
  await expect(items.first()).toBeVisible({ timeout: 15_000 });
  const count = await items.count();
  expect(count).toBeGreaterThanOrEqual(1);
  expect(count).toBeLessThanOrEqual(10);
  const times = await items.locator('time').evaluateAll((els) => els.map((el) => el.getAttribute('datetime') ?? ''));
  const millis = times.map((t) => new Date(/[Zz]$|[+-]\d{2}:?\d{2}$/.test(t) ? t : `${t}Z`).getTime());
  for (let i = 1; i < millis.length; i++) {
    expect(millis[i - 1]).toBeGreaterThanOrEqual(millis[i]);
  }
});

When('情報表示レールの操作ログ一覧へのリンクを選ぶ', async ({ page }) => {
  await rail(page).getByRole('link', { name: messagesJa.infoRail.viewAll }).click();
});

Then('操作ログページが表示される', async ({ page }) => {
  await expect(page).toHaveURL(/\/operation-logs/, { timeout: 15_000 });
});

let mainWidthBeforeRailCollapse = 0;

When('情報表示レールを折りたたむ', async ({ page }) => {
  await expect(rail(page)).toBeVisible({ timeout: 15_000 });
  mainWidthBeforeRailCollapse = (await page.locator('main').boundingBox())!.width;
  await expect(async () => {
    await rail(page).getByRole('button', { name: messagesJa.infoRail.collapse }).click({ timeout: 2_000 });
    await expect(rail(page)).toHaveAttribute('data-collapsed', 'true', { timeout: 1_000 });
  }).toPass({ timeout: 15_000 });
});

Then('本文の幅が情報表示レールを折りたたむ前より広くなる', async ({ page }) => {
  await expect
    .poll(async () => (await page.locator('main').boundingBox())!.width)
    .toBeGreaterThan(mainWidthBeforeRailCollapse);
});

Then('情報表示レールは折りたたまれたままである', async ({ page }) => {
  await expect(rail(page)).toHaveAttribute('data-collapsed', 'true', { timeout: 15_000 });
});

When('情報表示レールを展開する', async ({ page }) => {
  await expect(async () => {
    await rail(page).getByRole('button', { name: messagesJa.infoRail.expand }).click({ timeout: 2_000 });
    await expect(rail(page)).toHaveAttribute('data-collapsed', 'false', { timeout: 1_000 });
  }).toPass({ timeout: 15_000 });
});

Then(/^情報表示レールの「(.+)」タブが選択状態である$/, async ({ page }, label: string) => {
  await expect(tabButton(page, label)).toHaveAttribute('aria-pressed', 'true', { timeout: 15_000 });
});

Then('情報表示レールのドロワーは表示されない', async ({ page }) => {
  await expect(page.locator('main')).toBeVisible({ timeout: 15_000 });
  await expect(drawer(page)).toBeHidden();
  await expect(rail(page)).toBeHidden();
});

let mainWidthBeforeDrawer = 0;

When('情報表示レールのドロワーを開く', async ({ page }) => {
  mainWidthBeforeDrawer = (await page.locator('main').boundingBox())!.width;
  await clickUntilVisible(page.getByRole('button', { name: messagesJa.infoRail.open }), drawer(page));
});

Then('情報表示レールのドロワーが本文の幅を変えずに重なって表示される', async ({ page }) => {
  await expect(drawer(page)).toBeVisible();
  const d = (await drawer(page).boundingBox())!;
  const main = (await page.locator('main').boundingBox())!;
  expect(main.width).toBeCloseTo(mainWidthBeforeDrawer, 0);
  // ドロワーは本文の内側に食い込んで重なる。
  expect(d.x).toBeLessThan(main.x + main.width);
  expect(d.x + d.width).toBeGreaterThan(main.x);
});

When('情報表示レールのドロワーを閉じる', async ({ page }) => {
  await drawer(page).getByRole('button', { name: messagesJa.infoRail.close }).click();
});
