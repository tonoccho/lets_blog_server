import { test, expect } from '@playwright/test';
import { loginViaKeycloak } from './helpers';

/**
 * issue #564: NextAuthのCredentialsプロバイダを廃止しKeycloak(Authorization Code + PKCE)へ
 * 移行したことに伴い、Web自前のログイン/サインアップフォームを対象にしていた旧テストを
 * 全面的に書き換えた。/loginはマウント時にsignIn("keycloak")を呼んで即座にKeycloakのホスト型
 * ログイン画面へリダイレクトするだけの画面になったため、実際のフォーム操作はKeycloak側の
 * ページ(nginx経由でhttps://localhost/auth/realms/letsblog/...として提供される)に対して行う。
 * モックではなく実際に起動しているKeycloak/legacy-api/gatewayスタックへ疎通する
 * (playwright.config.tsのbaseURLがdocker composeのreverse-proxyを指すよう変更済み)。
 *
 * 使用するアカウントは、実ユーザー(s.tonouchi@gmail.com)ではなくこのテスト専用に
 * 発行した合成アカウント(identity-serviceのPOST /api/usersで作成し、Keycloak Admin APIで
 * パスワードを設定済み)。
 *   - e2e-test@letsblog.local  (role: user。非admin側の検証用)
 *   - e2e-admin@letsblog.local (role: admin。realmロールadminを付与済み。admin側の検証用)
 * パスワードはCI/ローンチ環境の環境変数E2E_TEST_PASSWORD/E2E_ADMIN_PASSWORDで注入する
 * (このリポジトリの.envには含めない。値はテスト account発行時のみ知りうる)。
 */

const TEST_EMAIL = 'e2e-test@letsblog.local';
const TEST_PASSWORD = process.env.E2E_TEST_PASSWORD ?? '';
const ADMIN_EMAIL = 'e2e-admin@letsblog.local';
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? '';

test.describe('Keycloak経由の認証フロー(issue #564)', () => {
  test.skip(!TEST_PASSWORD || !ADMIN_PASSWORD, 'E2E_TEST_PASSWORD/E2E_ADMIN_PASSWORDが未設定のためスキップ');

  test('ログイン画面にアクセスするとKeycloakのホスト型ログイン画面へリダイレクトされる', async ({ page }) => {
    await page.goto('/login');
    await page.waitForURL(/\/auth\/realms\/letsblog\//, { timeout: 15000 });
    await expect(page.locator('#username')).toBeVisible();
    await expect(page.locator('#password')).toBeVisible();
  });

  test('Keycloakで正しい資格情報を入力するとログインでき、セッションが確立する', async ({ page }) => {
    await loginViaKeycloak(page, TEST_EMAIL, TEST_PASSWORD);

    const logoutButton = page.locator('button:has-text("ログアウト")');
    await expect(logoutButton).toBeVisible({ timeout: 5000 });
  });

  test('誤ったパスワードではKeycloak側でエラーになりログインできない', async ({ page }) => {
    await page.goto('/login');
    await page.waitForURL(/\/auth\/realms\/letsblog\//, { timeout: 15000 });

    await page.locator('#username').fill(TEST_EMAIL);
    await page.locator('#password').fill('WrongPassword123!');
    await page.locator('#kc-login').click();

    // Keycloak側のエラー表示のまま留まり、Webのコールバックへは遷移しない。
    await expect(page).toHaveURL(/\/auth\/realms\/letsblog\//);
    await expect(page.getByText('Invalid username or password')).toBeVisible({ timeout: 5000 });
  });

  test('非管理者は管理者専用ページ(/users)へアクセスすると拒否される', async ({ page }) => {
    await loginViaKeycloak(page, TEST_EMAIL, TEST_PASSWORD);

    await page.goto('/users');
    // proxy.tsのADMIN_ONLY_PREFIXESにより"/"へリダイレクトされる。
    await expect(page).toHaveURL('/', { timeout: 5000 });
  });

  test('管理者は管理者専用ページ(/users)へアクセスできる', async ({ page }) => {
    await loginViaKeycloak(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    await page.goto('/users');
    await expect(page).toHaveURL(/\/users$/, { timeout: 5000 });
  });

  test('ログアウトするとセッションが破棄され、保護ページアクセス時にログイン画面へ戻る', async ({ page }) => {
    await loginViaKeycloak(page, TEST_EMAIL, TEST_PASSWORD);

    const logoutButton = page.locator('button:has-text("ログアウト")');
    await expect(logoutButton).toBeVisible();
    await logoutButton.click();

    await expect(page).toHaveURL(/\/login/, { timeout: 5000 });

    // ログアウト後に保護ページへ直接アクセスすると、再度Keycloakへリダイレクトされる
    // (events.signOutでKeycloak側のSSOセッションも終了させているため、資格情報の再入力を
    // 求めるホスト型ログイン画面が表示されるはず。ここではリダイレクト自体の発生のみ検証する)。
    await page.goto('/');
    await page.waitForURL(/\/(login|auth\/realms\/letsblog)/, { timeout: 10000 });
  });
});
