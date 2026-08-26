import { test, expect } from '@playwright/test';
import { loginViaKeycloak } from './helpers';

/**
 * issue #645: このファイルの大半のテストは `if (要素が存在すれば) { assert }` という形で
 * 書かれており、サイトが1件も登録されていない環境では常に無検証のままpassしていた
 * (「接続テスト」の待機処理も`.catch(() => null)`/`.catch(() => {...})`で例外を握りつぶしていた)。
 * beforeAllでManagedWordPressサイト(外部のSSHホストを必要としない自己完結型のフィクスチャ、
 * SiteCreationPanel.tsx/ManagedWordPressForm.tsx参照)を1件だけ構築し、以降の各テストが
 * このフィクスチャサイトを前提とした確定的な検証を行うようにする。
 * サイト登録操作(既存サイト登録・WordPress新規構築どちらも)はrequireAdminSession()で
 * 保護されているため、ログインにはadmin権限を持つe2e-admin@letsblog.local
 * (helpers.ts/auth-flow.spec.ts参照)を使う。
 */
const ADMIN_EMAIL = 'e2e-admin@letsblog.local';
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? '';

test.describe('Site Registration and Connection Flow', () => {
  test.skip(!ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  let fixtureSiteKey: string;
  let fixtureSiteName: string;

  test.beforeAll(async ({ browser }) => {
    const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    fixtureSiteKey = `e2efix-${unique}`;
    fixtureSiteName = `E2E Fixture Site ${unique}`;

    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    const page = await context.newPage();
    try {
      await loginViaKeycloak(page, ADMIN_EMAIL, ADMIN_PASSWORD);
      await page.goto('/sites');

      // Fixture: 常駐WordPressコンテナ上にサブディレクトリでWordPressを自動構築する
      // (外部SSHホストの用意が不要な、自己完結型のフィクスチャ)。
      await page.locator('id=site-creation').scrollIntoViewIfNeeded();
      await page.locator('button:has-text("WordPressを新規構築")').click();
      await page.locator('input[name="managedName"]').fill(fixtureSiteName);
      await page.locator('input[name="managedSiteKey"]').fill(fixtureSiteKey);
      await page.locator('input[name="managedTitle"]').fill(fixtureSiteName);
      await page.locator('input[name="managedAdminUser"]').fill('e2efixtureadmin');
      await page.locator('input[name="managedAdminEmail"]').fill('e2e-fixture-admin@letsblog.local');
      await page.locator('input[name="managedAdminPassword"]').fill('E2eFixture#Passw0rd1');

      // WordPressの自動構築は完了まで数分かかる場合がある(ManagedWordPressForm.tsx参照)。
      await page.locator('button:has-text("構築する")').click();
      await expect(page.getByText('構築しました。')).toBeVisible({ timeout: 240000 });
    } finally {
      await context.close();
    }
  });

  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/sites');
  });

  test('Navigate to sites page and view site list', async ({ page }) => {
    // Step 1: Verify sites page is loaded
    const heading = page.locator('h1:has-text("サイト")');
    await expect(heading).toBeVisible();

    // Step 2: Verify site list table is visible
    const siteTable = page.locator('table');
    await expect(siteTable).toBeVisible();
  });

  test('Site creation form is accessible', async ({ page }) => {
    // Step 1: Scroll to site creation section
    await page.locator('id=site-creation').scrollIntoViewIfNeeded();

    // Step 2: Verify site creation form is visible
    const siteCreationForm = page.locator('id=site-creation');
    await expect(siteCreationForm).toBeVisible();

    // Step 3: The mode-toggle buttons ("既存サイトを登録"/"WordPressを新規構築") always exist
    const createButtons = page.locator(
      'button:has-text("既存サイトを登録"), button:has-text("WordPressを新規構築")'
    );
    expect(await createButtons.count()).toBe(2);
    await expect(createButtons.first()).toBeVisible();
  });

  test('Test site connection for the fixture site', async ({ page }) => {
    // Step 1: The fixture site guarantees a matching row exists
    const fixtureRow = page.locator(`tr:has-text("${fixtureSiteKey}")`);
    await expect(fixtureRow).toBeVisible();

    // Step 2: Click its "疎通確認" button (CheckConnectionButton.tsx)
    const testButton = fixtureRow.locator('button:has-text("疎通確認")');
    await testButton.click();

    // Step 3: Verify the check resolves to either SUCCESS or FAILED (deterministic either/or,
    // not silently swallowed)
    const successBadge = fixtureRow.getByText('SUCCESS', { exact: true });
    const failedBadge = fixtureRow.getByText('FAILED', { exact: true });
    await expect(successBadge.or(failedBadge)).toBeVisible({ timeout: 15000 });
  });

  test('Sites page displays connection status controls for the fixture site', async ({ page }) => {
    // Step 1: The fixture site guarantees a matching row exists
    const fixtureRow = page.locator(`tr:has-text("${fixtureSiteKey}")`);
    await expect(fixtureRow).toBeVisible();

    // Step 2: Its row has a connection-check control
    await expect(fixtureRow.locator('button:has-text("疎通確認")')).toBeVisible();
  });

  test('Search filters the site list down to the fixture site', async ({ page }) => {
    // Step 1: Search input always exists (SiteListTable.tsx)
    const searchInput = page.locator('input[placeholder*="検索"]');
    await expect(searchInput).toBeVisible();

    // Step 2: Search for the fixture site's key
    await searchInput.fill(fixtureSiteKey);

    // Step 3: Verify the table still renders and the fixture site is present
    const siteTable = page.locator('table');
    await expect(siteTable).toBeVisible();
    await expect(page.locator(`td:has-text("${fixtureSiteKey}")`)).toBeVisible();
  });
});
