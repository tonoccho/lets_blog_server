import { Then, When } from './fixtures';
import { expect } from '../support';

/**
 * 認証ドメインのステップ定義(issue #926 / AT-0)。
 *
 * 現時点では AT-0 のサンプルシナリオ1件分のみ。認証の受け入れテスト本体は
 * AT-3(#929)がこのファイルへ追加する。
 */

When('ログイン画面を開く', async ({ page }) => {
  await page.goto('/login');
});

Then('Keycloakのホスト型ログイン画面が表示される', async ({ page }) => {
  // /login はマウント時に signIn("keycloak") を呼ぶだけの画面で、Keycloak の
  // ホスト型ログイン画面(reverse-proxy 経由 /auth/realms/letsblog/...)へ遷移する。
  await page.waitForURL(/\/auth\/realms\/letsblog\//, { timeout: 15000 });
  await expect(page.locator('#username')).toBeVisible();
  await expect(page.locator('#password')).toBeVisible();
});
