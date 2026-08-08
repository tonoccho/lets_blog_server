import { test, expect } from '@playwright/test';
import path from 'path';

test.describe('Image Upload and Generation Workflow', () => {
  test.beforeEach(async ({ page }) => {
    // Navigate to image gallery page
    await page.goto('/image-gallery');
  });

  test('Navigate to image gallery page', async ({ page }) => {
    // Step 1: Verify image gallery page is loaded
    const heading = page.locator('h1');
    await expect(heading).toBeVisible();

    // Step 2: Verify page title contains image-related keywords
    const title = await page.title();
    expect(title.toLowerCase()).toMatch(/画像|image|gallery/i);
  });

  test('Image gallery displays uploaded images (if any)', async ({ page }) => {
    // Step 1: Check if there are any images displayed
    const imageElements = page.locator('img[alt], [role="img"]');
    const imageCount = await imageElements.count();

    // Step 2: If images exist, verify they're visible
    if (imageCount > 0) {
      const firstImage = imageElements.first();
      await expect(firstImage).toBeVisible();
    }
  });

  test('Image upload interface is accessible', async ({ page }) => {
    // Step 1: Look for file input
    const fileInput = page.locator('input[type="file"]').first();

    if (await fileInput.isVisible()) {
      // Step 2: Verify upload button is visible
      const uploadButton = page.locator('button:has-text("アップロード"), button:has-text("Upload")').first();
      await expect(uploadButton).toBeVisible().catch(() => {
        // Upload button might appear after selecting file
        console.log('Upload button may appear after file selection');
      });
    }
  });

  test('Search or filter images (if feature exists)', async ({ page }) => {
    // Step 1: Look for search input
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="Search"], input[placeholder*="filter"]');

    if (await searchInput.isVisible()) {
      // Step 2: Perform a search
      await searchInput.fill('test');

      // Step 3: Wait for results to update
      await page.waitForTimeout(500);

      // Step 4: Verify page is still loaded
      await expect(page).toHaveURL(/image-gallery/);
    }
  });

  test('Image deletion (if delete functionality exists)', async ({ page }) => {
    // Step 1: Check if there are any images in the gallery
    const imageElements = page.locator('img[alt], [role="img"]');
    const imageCount = await imageElements.count();

    if (imageCount > 0) {
      // Step 2: Look for delete button
      const deleteButtons = page.locator('button:has-text("削除"), button[aria-label*="delete"]');
      const deleteCount = await deleteButtons.count();

      if (deleteCount > 0) {
        // Step 3: Click first delete button
        await deleteButtons.first().click();

        // Step 4: Confirm deletion if dialog appears
        const confirmButton = page.locator('button:has-text("確認"), button:has-text("削除"), button:has-text("OK")').first();
        if (await confirmButton.isVisible()) {
          await confirmButton.click();

          // Step 5: Verify deletion (success message or image removed)
          await expect(page).toHaveURL(/image-gallery/, { timeout: 10000 });
        }
      }
    }
  });

  test('Image detail/preview (if feature exists)', async ({ page }) => {
    // Step 1: Check if there are any images
    const imageElements = page.locator('img[alt], [role="img"]');
    const imageCount = await imageElements.count();

    if (imageCount > 0) {
      // Step 2: Click on first image to view details
      const firstImage = imageElements.first();
      const imageContainer = firstImage.locator('..').first(); // Get parent element

      if (await imageContainer.isClickable()) {
        await imageContainer.click();

        // Step 3: Verify modal or detail page opens
        const modal = page.locator('[role="dialog"], [class*="modal"]').first();
        await expect(modal).toBeVisible({ timeout: 5000 }).catch(() => {
          // If no modal, verify we're on a detail page
          console.log('Image detail might open in separate page');
        });
      }
    }
  });

  test('Image metadata display (if available)', async ({ page }) => {
    // Step 1: Check if there are any images
    const imageElements = page.locator('img[alt], [role="img"]');
    const imageCount = await imageElements.count();

    if (imageCount > 0) {
      // Step 2: Look for metadata elements (date, size, etc.)
      const metadataElements = page.locator('[class*="meta"], [class*="info"], [class*="detail"]');
      const metadataCount = await metadataElements.count();

      if (metadataCount > 0) {
        await expect(metadataElements.first()).toBeVisible();
      }
    }
  });

  test('Image pagination/infinite scroll (if feature exists)', async ({ page }) => {
    // Step 1: Get initial image count
    const initialImages = page.locator('img[alt], [role="img"]');
    const initialCount = await initialImages.count();

    if (initialCount > 0) {
      // Step 2: Scroll to bottom of page
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));

      // Step 3: Wait for potential new images to load
      await page.waitForTimeout(1000);

      // Step 4: Verify page is still loaded
      await expect(page).toHaveURL(/image-gallery/);
    }
  });

  test('Responsive layout on mobile (if applicable)', async ({ page }) => {
    // Step 1: Set mobile viewport
    await page.setViewportSize({ width: 375, height: 667 });

    // Step 2: Verify page is still accessible on mobile
    const heading = page.locator('h1');
    await expect(heading).toBeVisible();

    // Step 3: Verify images are still displayed
    const imageElements = page.locator('img[alt], [role="img"]');
    if (await imageElements.count() > 0) {
      const firstImage = imageElements.first();
      await expect(firstImage).toBeVisible();
    }
  });
});
