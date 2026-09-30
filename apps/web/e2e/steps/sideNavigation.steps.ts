import { expect } from '@playwright/test';
import { When, Then } from './fixtures';
import messagesJa from '../../messages/ja.json';
import { NAV_ITEMS, ADMIN_NAV_ITEMS } from '../../src/lib/navigation';
import { clickUntilVisible } from '../support/retryClick';

/**
 * 本文左の常設サイドメニュー(issue #1488)。
 * 「管理者としてログインする」「一般ユーザーとしてログインする」「ビューポート幅{N}pxで
 * ダッシュボードを開く」は既存の共通ステップを再利用する。
 */

const sideNav = (page: import('@playwright/test').Page) => page.getByTestId('side-nav');
const labelOf = (key: string) => (messagesJa.nav as Record<string, string>)[key];

Then('左メニューが本文より左に表示される', async ({ page }) => {
  await expect(sideNav(page)).toBeVisible({ timeout: 15_000 });
  const side = (await sideNav(page).boundingBox())!;
  const main = (await page.locator('main').boundingBox())!;
  expect(side.x + side.width).toBeLessThanOrEqual(main.x + 1);
});

Then('ヘッダーにナビゲーションが表示されない', async ({ page }) => {
  await expect(page.locator('header nav')).toHaveCount(0);
});

Then(/^左メニューの「(.+)」が選択状態で示される$/, async ({ page }, label: string) => {
  await expect(sideNav(page).getByRole('link', { name: label, exact: true })).toHaveAttribute(
    'aria-current',
    'page',
    { timeout: 15_000 }
  );
});

Then(/^左メニューの「(.+)」は選択状態で示されない$/, async ({ page }, label: string) => {
  await expect(sideNav(page).getByRole('link', { name: label, exact: true })).not.toHaveAttribute('aria-current', 'page');
});

When(/^左メニューの「(.+)」を選ぶ$/, async ({ page }, label: string) => {
  await sideNav(page).getByRole('link', { name: label, exact: true }).click();
  await page.waitForLoadState('load');
});

Then('左メニューに管理者向けの全項目がそのまま表示される', async ({ page }) => {
  for (const item of ADMIN_NAV_ITEMS) {
    await expect(sideNav(page).getByRole('link', { name: labelOf(item.labelKey), exact: true })).toBeVisible({
      timeout: 15_000,
    });
  }
  for (const item of NAV_ITEMS) {
    await expect(sideNav(page).getByRole('link', { name: labelOf(item.labelKey), exact: true })).toBeVisible();
  }
});

Then('左メニューに管理者向けの項目が表示されない', async ({ page }) => {
  await expect(sideNav(page)).toBeVisible({ timeout: 15_000 });
  for (const item of ADMIN_NAV_ITEMS) {
    await expect(page.getByRole('link', { name: labelOf(item.labelKey), exact: true })).toHaveCount(0);
  }
});

let widthBeforeCollapse = 0;

When('左メニューを折りたたむ', async ({ page }) => {
  await expect(sideNav(page)).toBeVisible({ timeout: 15_000 });
  widthBeforeCollapse = (await page.locator('main').boundingBox())!.width;
  // ハイドレーション前のクリックは空振りするため、折りたたまれるまで再試行する。
  await expect(async () => {
    await sideNav(page).getByRole('button', { name: messagesJa.sideNav.collapse }).click({ timeout: 2_000 });
    await expect(sideNav(page)).toHaveAttribute('data-collapsed', 'true', { timeout: 1_000 });
  }).toPass({ timeout: 15_000 });
});

Then('本文の幅が折りたたむ前より広くなる', async ({ page }) => {
  await expect
    .poll(async () => (await page.locator('main').boundingBox())!.width)
    .toBeGreaterThan(widthBeforeCollapse);
});

Then('左メニューは折りたたまれたままである', async ({ page }) => {
  await expect(sideNav(page)).toHaveAttribute('data-collapsed', 'true', { timeout: 15_000 });
});

When('ナビゲーションのドロワーを開く', async ({ page }) => {
  const open = page.getByRole('button', { name: messagesJa.sideNav.openMenu });
  const dialog = page.getByRole('dialog', { name: messagesJa.sideNav.navigation });
  await clickUntilVisible(open, dialog);
});

When('ナビゲーションのドロワーを閉じる', async ({ page }) => {
  // 開いたドロワーはヘッダーのトグルを覆うため、ドロワー内の閉じるボタンで閉じる。
  const dialog = page.getByRole('dialog', { name: messagesJa.sideNav.navigation });
  await dialog.getByRole('button', { name: messagesJa.sideNav.closeMenu }).click();
});

Then('ナビゲーションのドロワーは表示されない', async ({ page }) => {
  await expect(page.getByRole('dialog', { name: messagesJa.sideNav.navigation })).toBeHidden();
});

Then('横スクロールが発生しない', async ({ page }) => {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth
  );
  expect(overflow).toBeLessThanOrEqual(0);
});
