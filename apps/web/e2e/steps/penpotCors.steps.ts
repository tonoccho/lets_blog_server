import { Then, When } from './fixtures';
import { expect } from '../support';

/**
 * Penpot ログイン画面到達性 と CORS のステップ定義(issue #1093)。
 *
 * #1012 で `/penpot/` の中継先は直ったが、`PENPOT_PUBLIC_URI` が `http://localhost:9001`
 * のままのため、`https://localhost/penpot/` から見るとAPI呼び出しがクロスオリジンになり
 * CORSでブロックされる。「設定が読めること」ではなく、実際のブラウザで
 *   - ログインフォームが組み上がる(DOMに存在し、可視である)
 *   - その過程でCORSエラーがコンソールに出ない
 * ことを確かめる。
 */

/** Penpotのログインフォームの入力欄(#1093の調査で確認した実際のid)。 */
const EMAIL_SELECTOR = 'input#email[type="email"]';
const PASSWORD_SELECTOR = 'input#password[type="password"]';

/** ブラウザが出すCORSエラーメッセージに必ず含まれる文言。 */
const CORS_ERROR_MARKER = 'blocked by CORS policy';

When('{string} を開く', async ({ ctx, page }, url: string) => {
  const consoleErrors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      consoleErrors.push(msg.text());
    }
  });
  ctx.penpotConsoleErrors = consoleErrors;

  await page.goto(url, { waitUntil: 'networkidle' });
  // SPAがログイン画面を組み立てるまで、ネットワークアイドル後も少し猶予を見る
  // (get-profile失敗後のリトライ等、クライアント側の再描画が続くため)。
  await page.waitForTimeout(1500);
});

Then('ログインフォームのメールアドレス欄とパスワード欄が表示される', async ({ page }) => {
  await expect(page.locator(EMAIL_SELECTOR), 'メールアドレス欄が見つからない').toBeVisible();
  await expect(page.locator(PASSWORD_SELECTOR), 'パスワード欄が見つからない').toBeVisible();
});

Then('ブラウザコンソールにCORSエラーが出ていない', async ({ ctx }) => {
  const consoleErrors = (ctx.penpotConsoleErrors as string[] | undefined) ?? [];
  const corsErrors = consoleErrors.filter((text) => text.includes(CORS_ERROR_MARKER));
  expect(corsErrors, `CORSエラーが出ている:\n  ${corsErrors.join('\n  ')}`).toEqual([]);
});
