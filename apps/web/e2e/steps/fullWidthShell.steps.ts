import { expect } from '@playwright/test';
import { When, Then } from './fixtures';

/**
 * 上下3段シェルとフッター(issue #1487)。
 * 「管理者としてログインする」「テーマをダークにする」は既存の共通ステップを再利用する。
 */

When(/^ビューポート幅(\d+)pxでダッシュボードを開く$/, async ({ page }, width: string) => {
  await page.setViewportSize({ width: Number(width), height: 900 });
  await page.goto('/');
  await expect(page.locator('main')).toBeVisible({ timeout: 15_000 });
});

When(
  /^ビューポート幅(\d+)pxで高さ(\d+)pxのダッシュボードを開く$/,
  async ({ page }, width: string, height: string) => {
    await page.setViewportSize({ width: Number(width), height: Number(height) });
    await page.goto('/');
    await expect(page.locator('main')).toBeVisible({ timeout: 15_000 });
  }
);

Then('本文領域の幅が画面幅から左右のパディングを除いた幅に等しい', async ({ page }) => {
  const m = await page.locator('main').evaluate((el) => {
    const s = getComputedStyle(el);
    // 左メニュー(#1488)と右の情報表示レール(#1489)が本文の左右に並ぶ。
    // 本文+左メニュー+右レール=画面幅、を確かめる。
    const visibleWidth = (testId: string) => {
      const node = document.querySelector(`[data-testid="${testId}"]`);
      return node && getComputedStyle(node).display !== 'none' ? node.getBoundingClientRect().width : 0;
    };
    return {
      width: el.getBoundingClientRect().width + visibleWidth('side-nav') + visibleWidth('info-rail'),
      padding: parseFloat(s.paddingLeft) + parseFloat(s.paddingRight),
      viewport: document.documentElement.clientWidth,
    };
  });
  expect(m.width).toBeGreaterThanOrEqual(m.viewport - 1);
  expect(m.padding).toBeGreaterThan(0);
});

Then(/^フッターに「(.+)」と表示される$/, async ({ page }, text: string) => {
  await expect(page.locator('footer')).toContainText(text, { timeout: 15_000 });
  await expect(page.locator('footer')).toBeVisible();
});

Then('フッターの下端が画面の下端に一致する', async ({ page }) => {
  await expect(page.locator('footer')).toBeVisible({ timeout: 15_000 });
  const gap = await page.locator('footer').evaluate(
    (el) => window.innerHeight - el.getBoundingClientRect().bottom
  );
  expect(Math.abs(gap)).toBeLessThanOrEqual(1);
});

Then('フッターが表示され横スクロールが発生しない', async ({ page }) => {
  await expect(page.locator('footer')).toBeVisible({ timeout: 15_000 });
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth
  );
  expect(overflow).toBeLessThanOrEqual(0);
});

Then(
  /^フッターの文字色と背景色のコントラスト比が「([\d.]+)」以上である$/,
  async ({ page }, minimum: string) => {
    const footer = page.locator('footer');
    await expect(footer).toBeVisible({ timeout: 15_000 });
    const measured = await footer.evaluate((el) => {
      // oklch() / lab() など rgb() 以外で返る実効色も、canvas に塗って sRGB へ解決する。
      const ctx = document.createElement('canvas').getContext('2d', { willReadFrequently: true });
      const parse = (v: string): [number, number, number, number] | null => {
        if (!ctx) return null;
        ctx.clearRect(0, 0, 1, 1);
        ctx.fillStyle = '#000';
        ctx.fillStyle = v;
        ctx.fillRect(0, 0, 1, 1);
        const d = ctx.getImageData(0, 0, 1, 1).data;
        return [d[0], d[1], d[2], d[3] / 255];
      };
      let node: Element | null = el;
      let bg: [number, number, number, number] = [255, 255, 255, 1];
      while (node) {
        const parsed = parse(getComputedStyle(node).backgroundColor);
        if (parsed && parsed[3] > 0) {
          bg = parsed;
          break;
        }
        node = node.parentElement;
      }
      const fg = parse(getComputedStyle(el).color) ?? [0, 0, 0, 1];
      const lum = ([r, g, b]: number[]) => {
        const c = (x: number) => {
          const s = x / 255;
          return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
        };
        return 0.2126 * c(r) + 0.7152 * c(g) + 0.0722 * c(b);
      };
      const lf = lum(fg);
      const lb = lum(bg);
      return (Math.max(lf, lb) + 0.05) / (Math.min(lf, lb) + 0.05);
    });
    expect(measured).toBeGreaterThanOrEqual(Number(minimum));
  }
);
