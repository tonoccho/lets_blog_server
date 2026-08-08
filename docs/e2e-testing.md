# End-to-End (E2E) Testing Guide

This document describes how to run and maintain E2E tests for the Let's Blog Server web application using Playwright.

## Overview

E2E tests validate critical user workflows end-to-end, ensuring that key features work correctly from a user's perspective. The tests cover:

- **Authentication Flow**: User signup and login
- **Site Registration**: Site management and connection testing
- **Post/Article Creation**: Project and post management workflow
- **Image Upload**: Image gallery and upload functionality

## Prerequisites

- Node.js 18 or higher
- npm or yarn
- A running instance of the Let's Blog Server (for local testing)

## Test Files

E2E tests are located in `web/e2e/` directory:

- `auth-flow.spec.ts` - User authentication workflows (signup, login, logout)
- `site-registration.spec.ts` - Site management and connection testing
- `post-creation.spec.ts` - Project/article creation workflows
- `image-upload.spec.ts` - Image gallery and upload workflows
- `custom-tag-generation.spec.ts` - Custom tag generation (existing tests)
- `performance.spec.ts` - Performance testing (existing tests)
- `security.spec.ts` - Security validation tests (existing tests)

## Running Tests Locally

### 1. Install Dependencies

```bash
cd web
npm install
```

### 2. Start the Application

Ensure the Let's Blog Server is running:

```bash
# From the project root, using Docker Compose
docker compose up -d

# Or start the development server
cd web
npm run dev
```

The application should be accessible at `http://localhost:3000`.

### 3. Run All E2E Tests

```bash
cd web
npm run test:e2e
```

This runs all tests in headless mode (no browser UI).

### 4. Run E2E Tests with UI

To see tests running in a browser UI:

```bash
cd web
npm run test:e2e:ui
```

This opens Playwright's Test UI where you can:
- Watch tests run in real-time
- Pause and step through tests
- View test results and logs
- Re-run individual tests

### 5. Debug E2E Tests

To run tests in debug mode with step-by-step execution:

```bash
cd web
npm run test:e2e:debug
```

This opens Inspector mode where you can:
- Step through code execution
- Inspect page state
- Modify locators interactively

### 6. Run Specific Test File

```bash
cd web
npx playwright test e2e/auth-flow.spec.ts
```

### 7. Run Specific Test

```bash
cd web
npx playwright test e2e/auth-flow.spec.ts -g "Complete signup and login flow"
```

The `-g` flag filters tests by name pattern.

## Test Configuration

Playwright configuration is defined in `web/playwright.config.ts`. Key settings:

- **Base URL**: `http://localhost:3000`
- **Test Directory**: `./e2e`
- **Browsers**: Chromium, Firefox, WebKit, Mobile Chrome, Mobile Safari
- **Screenshot**: Captured on test failure only
- **Video**: Recorded only on test failure
- **Trace**: Enabled on first retry for debugging

## Environment Variables

The following environment variables can be configured:

- `CI`: Set to `true` in CI/CD environments
  - Enables retries (2 retries in CI, 0 locally)
  - Single worker mode in CI for stability
  - Forbids `test.only` tags

## CI/CD Integration

E2E tests run automatically in CI/CD pipeline for:
- Pull requests targeting `main` or `develop`
- Pushes to `main` or `develop` branches

The pipeline:
1. Installs dependencies
2. Runs linter and unit tests
3. Installs Playwright browsers
4. Runs all E2E tests
5. Uploads test reports as artifacts (retained for 30 days)

### Viewing CI Results

After tests run in GitHub Actions:
1. Go to the pull request or commit
2. Click "Checks" tab
3. View "Frontend Tests" results
4. Download "playwright-report" artifact to view detailed HTML report

## Writing New E2E Tests

### Test Structure

```typescript
import { test, expect } from '@playwright/test';

test.describe('Feature Name', () => {
  test.beforeEach(async ({ page }) => {
    // Setup: Navigate to page before each test
    await page.goto('/path');
  });

  test('descriptive test name', async ({ page }) => {
    // Arrange: Set up initial state
    const input = page.locator('input[name="field"]');

    // Act: Perform user action
    await input.fill('value');
    await page.locator('button').click();

    // Assert: Verify outcome
    await expect(page).toHaveURL('/expected-path');
  });
});
```

### Locator Strategies

Use these strategies for finding elements (in order of preference):

1. **Role locators**: `page.locator('role=button')`
2. **Test IDs**: `page.locator('[data-testid="submit"]')`
3. **Label text**: `page.locator('text=Login')`
4. **CSS selectors**: `page.locator('input[name="email"]')`
5. **XPath**: Last resort

### Best Practices

1. **Descriptive test names**: Use names that describe what's being tested
   - ✅ "Complete signup and login flow"
   - ❌ "test 1"

2. **Isolated tests**: Each test should be independent
   - Don't rely on test execution order
   - Use `test.beforeEach()` for setup

3. **Explicit waits**: Use Playwright's built-in waiting mechanisms
   - ❌ `await page.waitForTimeout(1000)` - avoid hard-coded delays
   - ✅ `await expect(element).toBeVisible()` - waits for condition

4. **Meaningful assertions**: Verify actual user outcomes
   - ❌ `expect(page).toBeDefined()`
   - ✅ `await expect(page).toHaveURL('/home')`

5. **Error handling**: Use `.catch()` for optional features
   ```typescript
   if (await element.isVisible()) {
     await element.click();
   }
   ```

6. **Use data attributes**: If possible, add `data-testid` attributes to components
   ```typescript
   <button data-testid="submit-button">Submit</button>
   ```

## Troubleshooting

### Tests timeout

**Symptom**: Tests fail with "Timeout" error

**Solutions**:
- Check if application is running on `http://localhost:3000`
- Increase timeout in test: `await expect(element).toBeVisible({ timeout: 15000 })`
- Check browser console for JavaScript errors

### Element not found

**Symptom**: "Selector failed to resolve" error

**Solutions**:
- Use `page.locator()` with [Playwright Inspector](https://playwright.dev/docs/inspector)
- Verify element exists in page
- Check if element loads after async action (use `expect().toBeVisible()`)

### Flaky tests (intermittent failures)

**Symptoms**: Tests pass sometimes, fail other times

**Solutions**:
- Replace hard-coded `waitForTimeout()` with condition-based waits
- Add explicit waits for network requests if needed
- Check if timing-dependent code exists
- Use `test.retries = 2` for CI environments only

### Browser crashes

**Symptom**: "Browser crashed" error

**Solutions**:
- Update Playwright: `npm install -D @playwright/test@latest`
- Check system resources (memory, disk space)
- Run in CI environment with `CI=true` flag

## Continuous Improvement

### Monitoring Test Health

- Review test reports after each CI run
- Track test execution times
- Identify flaky tests and fix root causes
- Keep tests synchronized with UI changes

### Updating Tests

When UI changes:
1. Identify affected tests
2. Update selectors/expectations
3. Run tests locally to verify
4. Commit changes with meaningful message

## Resources

- [Playwright Documentation](https://playwright.dev/)
- [Playwright Best Practices](https://playwright.dev/docs/best-practices)
- [Selector Inspector](https://playwright.dev/docs/inspector)
- [Debugging Guide](https://playwright.dev/docs/debug)

## Support

For issues with E2E tests:
1. Check this guide
2. Review test output and logs
3. Use Playwright Inspector to debug
4. Check GitHub issues for similar problems
5. Create a new issue with test output and reproduction steps
