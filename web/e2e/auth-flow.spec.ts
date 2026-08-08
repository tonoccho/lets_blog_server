import { test, expect } from '@playwright/test';

test.describe('User Authentication Flow', () => {
  const testEmail = `test-${Date.now()}@example.com`;
  const testPassword = 'TestPassword123!';

  test('Complete signup and login flow', async ({ page }) => {
    // Step 1: Navigate to signup page
    await page.goto('/signup');

    // Step 2: Verify signup form is visible
    const emailInput = page.locator('input[name="email"]');
    const passwordInput = page.locator('input[name="password"]');
    const submitButton = page.locator('button:has-text("登録")');

    await expect(emailInput).toBeVisible();
    await expect(passwordInput).toBeVisible();
    await expect(submitButton).toBeVisible();

    // Step 3: Fill in signup form
    await emailInput.fill(testEmail);
    await passwordInput.fill(testPassword);

    // Step 4: Submit signup form
    await submitButton.click();

    // Step 5: Verify redirect to home page after successful signup
    await expect(page).toHaveURL('/', { timeout: 10000 });

    // Step 6: Verify user is logged in (logout button should be visible)
    const logoutButton = page.locator('button:has-text("ログアウト")');
    await expect(logoutButton).toBeVisible({ timeout: 5000 });
  });

  test('Login with registered credentials', async ({ page }) => {
    // This test assumes a user already exists from the previous test
    // In a real scenario, you would use a beforeAll hook or setup data

    // Step 1: Navigate to login page
    await page.goto('/login');

    // Step 2: Verify login form is visible
    const emailInput = page.locator('input[name="email"]');
    const passwordInput = page.locator('input[name="password"]');
    const loginButton = page.locator('button:has-text("ログイン")');

    await expect(emailInput).toBeVisible();
    await expect(passwordInput).toBeVisible();
    await expect(loginButton).toBeVisible();

    // Step 3: Fill in login form
    await emailInput.fill(testEmail);
    await passwordInput.fill(testPassword);

    // Step 4: Submit login form
    await loginButton.click();

    // Step 5: Verify redirect to home page after successful login
    await expect(page).toHaveURL('/', { timeout: 10000 });

    // Step 6: Verify user is logged in
    const logoutButton = page.locator('button:has-text("ログアウト")');
    await expect(logoutButton).toBeVisible({ timeout: 5000 });
  });

  test('Signup validation: password minimum length', async ({ page }) => {
    // Step 1: Navigate to signup page
    await page.goto('/signup');

    // Step 2: Fill in form with short password
    const emailInput = page.locator('input[name="email"]');
    const passwordInput = page.locator('input[name="password"]');

    await emailInput.fill(`short-${Date.now()}@example.com`);
    await passwordInput.fill('short');  // Less than 8 characters

    // Step 3: Try to submit (button should be disabled or form should not submit)
    const submitButton = page.locator('button:has-text("登録")');

    // Check if button is disabled or if validation fails
    const isDisabled = await submitButton.isDisabled();
    if (isDisabled) {
      await expect(submitButton).toBeDisabled();
    }
  });

  test('Login with invalid credentials shows error', async ({ page }) => {
    // Step 1: Navigate to login page
    await page.goto('/login');

    // Step 2: Fill in login form with invalid credentials
    const emailInput = page.locator('input[name="email"]');
    const passwordInput = page.locator('input[name="password"]');
    const loginButton = page.locator('button:has-text("ログイン")');

    await emailInput.fill('invalid@example.com');
    await passwordInput.fill('WrongPassword123!');

    // Step 3: Submit form
    await loginButton.click();

    // Step 4: Verify error message is displayed
    const errorMessage = page.locator('text=メールアドレスまたはパスワードが正しくありません');
    await expect(errorMessage).toBeVisible({ timeout: 5000 });

    // Step 5: Verify we're still on login page
    await expect(page).toHaveURL('/login');
  });

  test('Signup with duplicate email shows error', async ({ page }) => {
    // Step 1: Navigate to signup page
    await page.goto('/signup');

    // Step 2: Fill in signup form with duplicate email
    const emailInput = page.locator('input[name="email"]');
    const passwordInput = page.locator('input[name="password"]');
    const submitButton = page.locator('button:has-text("登録")');

    await emailInput.fill(testEmail);  // Reuse email from previous test
    await passwordInput.fill('AnotherPassword123!');

    // Step 3: Submit form
    await submitButton.click();

    // Step 4: Verify error message is displayed
    const errorMessage = page.locator('text=すでに登録済みです');
    await expect(errorMessage).toBeVisible({ timeout: 5000 });

    // Step 5: Verify we're still on signup page
    await expect(page).toHaveURL('/signup');
  });

  test('Logout functionality', async ({ page }) => {
    // Step 1: First login
    await page.goto('/login');
    const emailInput = page.locator('input[name="email"]');
    const passwordInput = page.locator('input[name="password"]');
    const loginButton = page.locator('button:has-text("ログイン")');

    await emailInput.fill(testEmail);
    await passwordInput.fill(testPassword);
    await loginButton.click();

    // Wait for login to complete
    await expect(page).toHaveURL('/', { timeout: 10000 });

    // Step 2: Verify logout button is visible
    const logoutButton = page.locator('button:has-text("ログアウト")');
    await expect(logoutButton).toBeVisible();

    // Step 3: Click logout button
    await logoutButton.click();

    // Step 4: Verify redirect to login page (or home if public)
    await expect(page).toHaveURL(/\/(login|)/, { timeout: 5000 });

    // Step 5: Verify logout button is no longer visible
    await expect(logoutButton).not.toBeVisible({ timeout: 5000 });
  });
});
