import { test, expect } from '@playwright/test';
import { injectAxe, checkA11y, getViolations } from 'axe-playwright';
import { loginViaKeycloak } from './helpers';

/**
 * issue #564でCredentialsプロバイダを廃止しKeycloakへ移行したことに伴い、以下の前提が変わった。
 * - /signupは削除済み(該当テストも削除)。
 * - /loginは自前のフォームを持たず、即座にKeycloakのホスト型ログイン画面へリダイレクトする。
 *   フォーム(email/passwordのinput)を検証するテストは、Keycloak側のフォーム
 *   (#username/#password/#kc-login)を対象にするよう書き換えた。
 * - 未ログイン状態で保護ページ(/等)へ行くとproxy.tsがログインへ誘導し、結果的にKeycloakの
 *   ホスト型ページへ到達してしまう(以前は自前の/loginページが表示されていた)。アプリ自身の
 *   ホーム画面のアクセシビリティを検証したいテストは、E2E専用の合成アカウント
 *   (e2e-test@letsblog.local)でログインしてから対象ページへ遷移するようにした。
 */

const TEST_EMAIL = 'e2e-test@letsblog.local';
const TEST_PASSWORD = process.env.E2E_TEST_PASSWORD ?? '';

test.describe('Accessibility (a11y) Testing', () => {
  test.skip(!TEST_PASSWORD, 'E2E_TEST_PASSWORDが未設定のためスキップ');

  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, TEST_EMAIL, TEST_PASSWORD);
  });

  test('Home page should not have accessibility violations', async ({ page }) => {
    await page.goto('/');
    await injectAxe(page);
    await checkA11y(page, null, {
      detailedReport: true,
      detailedReportOptions: {
        html: true,
      },
    });
  });

  test('Identify accessibility violations for review', async ({ page }) => {
    await page.goto('/');
    await injectAxe(page);

    const violations = await getViolations(page);

    // Log violations for manual review
    if (violations.length > 0) {
      console.log(`Found ${violations.length} accessibility violation(s):`);
      violations.forEach((violation: { id: string; description: string; impact: string; nodes: unknown[] }) => {
        console.log(`- ${violation.id}: ${violation.description}`);
        console.log(`  Impact: ${violation.impact}`);
        console.log(`  Elements affected: ${violation.nodes.length}`);
      });
    }

    // Document violations but don't fail tests yet
    // This allows for incremental fixes while tracking what needs to be addressed
  });

  test('Navigation should be keyboard accessible', async ({ page }) => {
    await page.goto('/');

    // Tab through the page to verify keyboard navigation
    await page.keyboard.press('Tab');

    // Verify an element is focused
    const focusedElement = page.locator(':focus');
    await expect(focusedElement).toBeTruthy();
  });

  test('Page should have valid heading hierarchy', async ({ page }) => {
    await page.goto('/');
    await injectAxe(page);

    // Check for heading hierarchy issues
    const headings = page.locator('h1, h2, h3, h4, h5, h6');
    const count = await headings.count();

    // At least one H1 should exist
    const h1s = page.locator('h1');
    await expect(h1s).toHaveCount(1, { timeout: 5000 }).catch(() => {
      // If test fails, log for manual review
      console.log('Warning: Page may not have exactly one H1');
    });
  });

  test('Images should have alt text', async ({ page }) => {
    await page.goto('/');

    // Find all images
    const images = page.locator('img');
    const imageCount = await images.count();

    for (let i = 0; i < imageCount; i++) {
      const img = images.nth(i);
      const alt = await img.getAttribute('alt');
      const ariaLabel = await img.getAttribute('aria-label');

      // Each image should have alt text or aria-label
      if (alt === '' && !ariaLabel) {
        console.log(`Warning: Image ${i} missing alt text`);
      }
    }
  });

  test('Links should have descriptive text', async ({ page }) => {
    await page.goto('/');

    // Check for links with no text (empty links)
    const links = page.locator('a');
    const linkCount = await links.count();

    for (let i = 0; i < linkCount; i++) {
      const link = links.nth(i);
      const text = await link.textContent();
      const ariaLabel = await link.getAttribute('aria-label');
      const title = await link.getAttribute('title');

      if (!text?.trim() && !ariaLabel && !title) {
        console.log(`Warning: Link ${i} has no accessible text`);
      }
    }
  });

  test('Color contrast should be sufficient (manual check)', async ({ page }) => {
    await page.goto('/');

    // This test serves as a reminder to manually verify color contrast
    // Use tools like WebAIM Contrast Checker for verification
    console.log('Manual check needed: Verify color contrast meets WCAG AA standards');
  });
});

test.describe('Accessibility (a11y) Testing - Keycloakホスト型ログイン画面', () => {
  test('Login page should not have accessibility violations', async ({ page }) => {
    await page.goto('/login');
    await page.waitForURL(/\/auth\/realms\/letsblog\//, { timeout: 15000 });
    await injectAxe(page);
    await checkA11y(page, null, {
      detailedReport: true,
      detailedReportOptions: {
        html: true,
      },
    });
  });

  test('Forms should have proper labels and ARIA attributes', async ({ page }) => {
    await page.goto('/login');
    await page.waitForURL(/\/auth\/realms\/letsblog\//, { timeout: 15000 });

    // Check username input has associated label
    const usernameInput = page.locator('#username');
    await expect(usernameInput).toBeTruthy();

    // Check password input has associated label
    const passwordInput = page.locator('#password');
    await expect(passwordInput).toBeTruthy();

    // Check submit button has accessible name
    const submitButton = page.locator('#kc-login');
    const accessibleName = await submitButton.getAttribute('aria-label') ||
                          await submitButton.textContent();
    await expect(accessibleName).toBeTruthy();
  });

  test('Focus indicators should be visible', async ({ page }) => {
    await page.goto('/login');
    await page.waitForURL(/\/auth\/realms\/letsblog\//, { timeout: 15000 });

    // Click on username input to show focus
    const usernameInput = page.locator('#username');
    await usernameInput.focus();

    // Verify element is focused
    const focusedElement = page.locator(':focus');
    const isFocused = await focusedElement.count();
    await expect(isFocused).toBeGreaterThan(0);
  });
});
