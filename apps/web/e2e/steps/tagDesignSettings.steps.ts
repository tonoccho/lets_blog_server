import type { Page } from '@playwright/test';
import { Then, When } from './fixtures';
import { expect } from '../support';

/**
 * タグデザイン設定の保存と、グローバル/プロジェクト個別の独立性(issue #1155)。
 *
 * 「管理者としてログインする」「ページを再読み込みする」「タグデザインの動作を確かめるための
 * プロジェクトがある」(後者は tagDesign.steps.ts、`@project` の After で削除も行う)は
 * 既存の共通ステップを再利用する。ここでは同じ文言を再定義しない。
 */

type ScenarioState = Record<string, unknown>;
interface Design {
  background: string;
  css: string;
}

function randomHex(): string {
  return `#${Math.floor(Math.random() * 0xffffff)
    .toString(16)
    .padStart(6, '0')}`;
}

function uniqueDesign(): Design {
  const marker = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  return {
    background: randomHex(),
    css: `.e2e1155-${marker}{color:rgb(1,2,3);}`,
  };
}

async function readDesign(page: Page): Promise<Design> {
  const textarea = page.locator('textarea[name="customCss"]');
  // CSS欄はハイドレーション完了(mounted)までdisabled(#1144)。値の読み取りも完了を待つ。
  await expect(textarea).toBeEnabled({ timeout: 15_000 });
  return {
    background: await page.locator('input[name="backgroundColor"]').inputValue(),
    css: await textarea.inputValue(),
  };
}

async function saveUniqueDesign(page: Page, design: Design): Promise<void> {
  const textarea = page.locator('textarea[name="customCss"]');
  await expect(textarea).toBeEnabled({ timeout: 15_000 });
  // 色を先に変える(CSSが自動生成のままだと色変更でCSSが再生成されるため)。
  await page.locator('input[name="backgroundColor"]').fill(design.background);
  await textarea.fill(design.css);
  await page
    .locator('form')
    .filter({ has: textarea })
    .locator('button[type="submit"]')
    .click();
  await expect(page.getByText('保存しました。')).toBeVisible({ timeout: 15_000 });
}

async function openTocEditor(page: Page, url: string): Promise<void> {
  await page.goto(url, { waitUntil: 'load' });
  await page.getByRole('row').filter({ hasText: '[toc]' }).getByRole('button', { name: '編集' }).click();
}

function projectTagsUrl(ctx: ScenarioState): string {
  return `/projects/${ctx.tagDesignProjectId as number}/tags`;
}

When('グローバルタグデザイン画面で[toc]の色とCSSに一意な値を保存する', async ({ ctx, page }) => {
  const design = uniqueDesign();
  (ctx as ScenarioState).tagDesignGlobal = design;
  (ctx as ScenarioState).tagDesignExpected = design;
  await openTocEditor(page, '/admin/tag-design');
  await saveUniqueDesign(page, design);
});

When('グローバルタグデザイン画面で[toc]の色とCSSに別の一意な値を保存する', async ({ ctx, page }) => {
  const design = uniqueDesign();
  (ctx as ScenarioState).tagDesignGlobal = design;
  await openTocEditor(page, '/admin/tag-design');
  await saveUniqueDesign(page, design);
});

When('プロジェクトの組み込みタグデザイン画面を開く', async ({ ctx, page }) => {
  await openTocEditor(page, projectTagsUrl(ctx as ScenarioState));
});

When('プロジェクトの組み込みタグデザイン画面を開いて表示中の値を控える', async ({ ctx, page }) => {
  await openTocEditor(page, projectTagsUrl(ctx as ScenarioState));
  (ctx as ScenarioState).tagDesignBaseline = await readDesign(page);
});

When('組み込みタグデザインの編集欄で[toc]の色とCSSに一意な値を保存する', async ({ ctx, page }) => {
  const design = uniqueDesign();
  (ctx as ScenarioState).tagDesignExpected = design;
  await saveUniqueDesign(page, design);
});

Then('組み込みタグデザインの編集欄に保存した色とCSSが表示される', async ({ ctx, page }) => {
  const expected = (ctx as ScenarioState).tagDesignExpected as Design;
  const actual = await readDesign(page);
  expect(actual.css).toBe(expected.css);
  expect(actual.background.toLowerCase()).toBe(expected.background.toLowerCase());
});

Then('組み込みタグデザインの編集欄は控えた標準の値のままである', async ({ ctx, page }) => {
  const baseline = (ctx as ScenarioState).tagDesignBaseline as Design;
  const actual = await readDesign(page);
  expect(actual).toEqual(baseline);
});

Then('組み込みタグデザインの編集欄にグローバルで保存した色とCSSは表示されていない', async ({ ctx, page }) => {
  const global = (ctx as ScenarioState).tagDesignGlobal as Design;
  const actual = await readDesign(page);
  expect(actual.css).not.toContain(global.css);
  expect(actual.background.toLowerCase()).not.toBe(global.background.toLowerCase());
});
