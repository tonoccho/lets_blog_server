import { expect } from '@playwright/test';
import { Given, When, Then } from './fixtures';

/**
 * テーマ初期化スクリプト(issue #1238)。
 *
 * `/login` はKeycloakへ遷移するため、ハイドレート後の観測は認証不要で遷移しない
 * ルートレイアウト描画済みの404画面で行う。
 */
const THEME_CHECK_PATH = '/login/theme-check';

/** `document.documentElement.setAttribute("data-theme"` を含む script が「テーマ初期化スクリプト」。 */
const THEME_MARKER = 'setAttribute("data-theme"';

const SCRIPT_TAG_WARNING = 'Encountered a script tag while rendering React component';

/** `<head>...</head>` の部分文字列から、テーマ初期化スクリプトの個数を数える。 */
function countThemeScripts(headHtml: string): number {
  return headHtml.split('</script>').filter((chunk) => chunk.includes(THEME_MARKER)).length;
}

When('ログイン画面のSSR HTMLを取得する', async ({ request, ctx }) => {
  const response = await request.get('/login', { maxRedirects: 0 });
  expect(response.status()).toBe(200);
  ctx.themeSsrHtml = await response.text();
});

Then('head終了タグより前にテーマ初期化スクリプトがちょうど1個含まれる', async ({ ctx }) => {
  const html = ctx.themeSsrHtml as string;
  const headEnd = html.indexOf('</head>');
  expect(headEnd, 'SSR HTMLに</head>が無い').toBeGreaterThan(-1);
  expect(countThemeScripts(html.slice(0, headEnd))).toBe(1);
});

Then('SSR HTML全体でテーマ初期化スクリプトはちょうど1個だけである', async ({ ctx }) => {
  const html = ctx.themeSsrHtml as string;
  const headEnd = html.indexOf('</head>');
  // 生のスクリプト(引用符がエスケープされていない形)だけを数える。ペイロード内の文字列表現は含めない。
  const total = countThemeScripts(html);
  expect(
    total,
    `SSR HTML全体のテーマ初期化スクリプトが${total}個ある(1個であるべき。flushされたチャンクごとに再出力されている: issue #1238)`
  ).toBe(1);
  expect(html.indexOf(THEME_MARKER), 'スクリプトが</head>より後ろにある').toBeLessThan(headEnd);
});

Given(/^OSの配色設定が「(dark|light)」である$/, async ({ page }, scheme: 'dark' | 'light') => {
  await page.emulateMedia({ colorScheme: scheme });
});

When('テーマ確認用の公開ページを開く', async ({ page, ctx }) => {
  const messages: string[] = [];
  page.on('console', (msg) => messages.push(msg.text()));
  page.on('pageerror', (err) => messages.push(err.message));
  ctx.themeConsoleMessages = messages;
  await page.goto(THEME_CHECK_PATH, { waitUntil: 'networkidle' });
  // ハイドレーションとdevビルドの警告出力に猶予を見る。
  await page.waitForTimeout(1500);
});

Then(/^html要素のdata-themeが「(dark|light)」になる$/, async ({ page }, expected: string) => {
  await expect(page.locator('html')).toHaveAttribute('data-theme', expected);
});

Then('ハイドレート後もhead内のテーマ初期化スクリプトは1個である', async ({ page }) => {
  const headHtml = await page.evaluate(() => document.head.innerHTML);
  expect(countThemeScripts(headHtml)).toBe(1);
});

Then('コンソールにscriptタグ描画の警告が記録されない', async ({ ctx }) => {
  const messages = (ctx.themeConsoleMessages as string[] | undefined) ?? [];
  const hits = messages.filter((text) => text.includes(SCRIPT_TAG_WARNING));
  expect(hits, `React 19のscriptタグ警告が記録されている(issue #1238):\n  ${hits.join('\n  ')}`).toEqual([]);
});

Then('テーマ初期化スクリプトのソースはself.__next_fのペイロードに含まれない', async ({ ctx }) => {
  const html = ctx.themeSsrHtml as string;
  // ペイロードは文字列リテラルとして埋め込まれるため、引用符が `\\"` の形にエスケープされる。
  // 生のインラインスクリプト(バックスラッシュ無し)には一致しない。
  const inPayload = /setAttribute\(\\+"data-theme/.test(html);
  expect(
    inPayload,
    'テーマ初期化スクリプトがReactのクライアント向けペイロードに<script>要素として載っている(issue #1238)'
  ).toBe(false);
});
