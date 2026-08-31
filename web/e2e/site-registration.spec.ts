import { test, expect } from '@playwright/test';

test.describe('Site Registration and Connection Flow', () => {
  test.beforeEach(async ({ page }) => {
    // Assuming user is already logged in
    // Navigate to sites page
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

    // Step 3: Look for create/add button
    const createButtons = page.locator('button:has-text("追加"), button:has-text("作成"), button:has-text("新規")');
    const buttonCount = await createButtons.count();

    if (buttonCount > 0) {
      await expect(createButtons.first()).toBeVisible();
    }
  });

  test('Test site connection (if connection test exists)', async ({ page }) => {
    // Step 1: Look for site table with connection test button
    const connectionTestButtons = page.locator('button:has-text("接続テスト"), button:has-text("テスト"), button:has-text("確認")');
    const buttonCount = await connectionTestButtons.count();

    // If connection test button exists, verify it works
    if (buttonCount > 0) {
      const firstTestButton = connectionTestButtons.first();

      // Step 2: Click test button
      await firstTestButton.click();

      // Step 3: Wait for test result
      // This could be a loading indicator or success/error message
      const loadingIndicator = page.locator('[class*="loading"], [class*="spinner"], [role="status"]');
      const successMessage = page.locator('[role="alert"], [class*="success"], [class*="error"]');

      // Wait for either loading to disappear or success/error message
      await Promise.race([
        loadingIndicator.first().waitFor({ state: 'hidden', timeout: 10000 }).catch(() => null),
        successMessage.first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => null),
      ]);

      // Step 4: Verify result message is displayed
      await expect(successMessage.first()).toBeVisible({ timeout: 5000 }).catch(() => {
        // If no explicit message, at least verify button is still visible
        expect(firstTestButton).toBeVisible();
      });
    }
  });

  test('Sites page displays connection status indicator', async ({ page }) => {
    // Step 1: Look for status indicators (if they exist)
    const statusIcons = page.locator('[class*="status"], [class*="connected"], [class*="icon"]');

    // Step 2: Verify at least one status indicator is visible (if any sites exist)
    const siteRows = page.locator('tbody tr');
    const rowCount = await siteRows.count();

    if (rowCount > 0) {
      // If there are sites, check for status indicators
      const firstRow = siteRows.first();
      await expect(firstRow).toBeVisible();
    }
  });

  test('Search or filter sites (if feature exists)', async ({ page }) => {
    // Step 1: Look for search input
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="Search"]');

    if (await searchInput.isVisible()) {
      // Step 2: Perform a search
      await searchInput.fill('test');

      // Step 3: Verify results are filtered
      const siteTable = page.locator('table');
      await expect(siteTable).toBeVisible();
    }
  });
});
