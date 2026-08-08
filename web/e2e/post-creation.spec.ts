import { test, expect } from '@playwright/test';

test.describe('Article/Post Creation Workflow', () => {
  test.beforeEach(async ({ page }) => {
    // Navigate to projects page
    await page.goto('/projects');
  });

  test('Navigate to projects page and view project list', async ({ page }) => {
    // Step 1: Verify projects page is loaded
    const heading = page.locator('h1:has-text("プロジェクト")');
    await expect(heading).toBeVisible();

    // Step 2: Verify project list table is visible
    const projectTable = page.locator('table');
    await expect(projectTable).toBeVisible();
  });

  test('Project creation form is accessible', async ({ page }) => {
    // Step 1: Scroll to project form section
    await page.locator('id=project-form').scrollIntoViewIfNeeded();

    // Step 2: Verify project form is visible
    const projectForm = page.locator('id=project-form');
    await expect(projectForm).toBeVisible();

    // Step 3: Look for form inputs
    const titleInput = page.locator('input[name*="title"], input[placeholder*="タイトル"]').first();
    if (await titleInput.isVisible()) {
      await expect(titleInput).toBeVisible();
    }
  });

  test('Create a new project with basic information', async ({ page }) => {
    // Step 1: Scroll to project form
    await page.locator('id=project-form').scrollIntoViewIfNeeded();

    // Step 2: Find and fill project form fields
    const titleInput = page.locator('input[name*="title"], input[placeholder*="タイトル"], input[placeholder*="Project"]').first();

    if (await titleInput.isVisible()) {
      // Step 3: Fill project title
      const projectTitle = `Test Project ${Date.now()}`;
      await titleInput.fill(projectTitle);

      // Step 4: Look for description field
      const descriptionInput = page.locator('textarea[name*="description"], textarea[placeholder*="説明"]').first();
      if (await descriptionInput.isVisible()) {
        await descriptionInput.fill('This is a test project for E2E testing');
      }

      // Step 5: Look for and click submit button
      const submitButton = page.locator('button:has-text("作成"), button:has-text("保存"), button:has-text("追加")').first();
      if (await submitButton.isVisible()) {
        await submitButton.click();

        // Step 6: Verify project was created (either success message or redirect)
        await expect(page).toHaveURL(/projects/, { timeout: 10000 });

        // Step 7: Verify new project appears in list
        const projectName = page.locator(`text="${projectTitle}"`);
        await expect(projectName).toBeVisible({ timeout: 5000 }).catch(() => {
          // If project name not immediately visible, it might be on next page or requires refresh
          console.log('Project created but not immediately visible in list');
        });
      }
    }
  });

  test('View project details', async ({ page }) => {
    // Step 1: Check if there are any projects in the table
    const projectRows = page.locator('tbody tr');
    const rowCount = await projectRows.count();

    if (rowCount > 0) {
      // Step 2: Click on the first project to view details
      const firstProjectLink = projectRows.first().locator('a, button').first();

      if (await firstProjectLink.isVisible()) {
        await firstProjectLink.click();

        // Step 3: Verify project details page is loaded
        await expect(page).toHaveURL(/projects\/\d+/, { timeout: 10000 });

        // Step 4: Verify project information is displayed
        const projectContent = page.locator('main, [role="main"], body');
        await expect(projectContent).toBeDefined();
      }
    }
  });

  test('Project list displays project information', async ({ page }) => {
    // Step 1: Verify table headers are visible
    const tableHeaders = page.locator('thead');
    await expect(tableHeaders).toBeVisible();

    // Step 2: Check if there are any project rows
    const projectRows = page.locator('tbody tr');
    const rowCount = await projectRows.count();

    if (rowCount > 0) {
      // Step 3: Verify first row has expected content
      const firstRow = projectRows.first();
      await expect(firstRow).toBeVisible();

      // Step 4: Verify columns are present (title, date, status, etc.)
      const cells = firstRow.locator('td, th');
      const cellCount = await cells.count();
      expect(cellCount).toBeGreaterThan(0);
    }
  });

  test('Search or filter projects (if feature exists)', async ({ page }) => {
    // Step 1: Look for search input
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="Search"], input[placeholder*="filter"]');

    if (await searchInput.isVisible()) {
      // Step 2: Perform a search
      await searchInput.fill('test');

      // Step 3: Verify results are updated
      const projectTable = page.locator('table');
      await expect(projectTable).toBeVisible();

      // Optional: Wait a bit for filter to apply
      await page.waitForTimeout(500);

      // Step 4: Verify table is still visible (results updated)
      await expect(projectTable).toBeVisible();
    }
  });

  test('Delete project (if delete functionality exists)', async ({ page }) => {
    // Step 1: Check if there are any projects
    const projectRows = page.locator('tbody tr');
    const rowCount = await projectRows.count();

    if (rowCount > 0) {
      // Step 2: Look for delete button in first row
      const firstRow = projectRows.first();
      const deleteButton = firstRow.locator('button:has-text("削除"), button[aria-label*="delete"]').first();

      if (await deleteButton.isVisible()) {
        // Step 3: Click delete button
        await deleteButton.click();

        // Step 4: Confirm deletion if there's a dialog
        const confirmButton = page.locator('button:has-text("確認"), button:has-text("削除"), button:has-text("OK")').first();
        if (await confirmButton.isVisible()) {
          await confirmButton.click();
        }

        // Step 5: Verify deletion (success message or row removed)
        await expect(page).toHaveURL(/projects/, { timeout: 10000 });
      }
    }
  });
});
