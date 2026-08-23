import type { Page } from '@playwright/test';

/**
 * issue #564: Keycloakへの移行に伴い、/loginは自前フォームを持たずKeycloakのホスト型
 * ログイン画面へ即座にリダイレクトするようになった。E2Eからログイン状態を作るには、
 * このホスト型フォームへ実際に値を入力してサインインを完了させる必要がある。
 * auth-flow.spec.ts/accessibility.spec.tsの両方から使う共通ヘルパー。
 *
 * 使用するアカウントは実ユーザー(s.tonouchi@gmail.com)ではなく、このE2E専用に発行した
 * 合成アカウント(e2e-test@letsblog.local / e2e-admin@letsblog.local)。
 */
export async function loginViaKeycloak(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.waitForURL(/\/auth\/realms\/letsblog\//, { timeout: 15000 });

  await page.locator('#username').fill(email);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();

  // VERIFY_PROFILE等の追加required actionが出た場合のみ処理する(通常のログインでは出ない)。
  if (await page.locator('#firstName').isVisible({ timeout: 3000 }).catch(() => false)) {
    await page.locator('#firstName').fill('E2E');
    await page.locator('#lastName').fill('Test');
    await page.locator('input[type="submit"]').first().click();
  }

  await page.waitForURL('/', { timeout: 15000 });
}
