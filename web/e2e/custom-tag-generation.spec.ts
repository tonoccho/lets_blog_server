import { test, expect } from '@playwright/test';

test.describe('カスタムタグ生成フロー', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/custom-tags');
  });

  test('正常系: プロンプト入力からタグ生成・保存までの完全フロー', async ({ page }) => {
    // Step 1: プロンプト入力フィールドが表示されていることを確認
    const promptInput = page.locator('textarea[name="prompt"]');
    await expect(promptInput).toBeVisible();

    // Step 2: プロンプトを入力
    await promptInput.fill('青いボタンコンポーネントを作成してください');

    // Step 3: タグ名を入力
    const tagNameInput = page.locator('input[name="tagName"]');
    await expect(tagNameInput).toBeVisible();
    await tagNameInput.fill('my-button');

    // Step 4: 説明を入力
    const descriptionInput = page.locator('textarea[name="description"]');
    await expect(descriptionInput).toBeVisible();
    await descriptionInput.fill('カスタムボタンコンポーネント');

    // Step 5: 生成ボタンをクリック
    const generateButton = page.locator('button:has-text("生成する")');
    await generateButton.click();

    // Step 6: 生成中のローディング状態を確認
    const loadingIndicator = page.locator('[data-testid="loading-indicator"]');
    await expect(loadingIndicator).toBeVisible();

    // Step 7: 生成完了後、プレビューが表示されることを確認
    await expect(loadingIndicator).not.toBeVisible({ timeout: 15000 });
    const htmlPreview = page.locator('[data-testid="html-preview"]');
    await expect(htmlPreview).toBeVisible();

    // Step 8: HTMLプレビューが正しく表示されていることを確認
    await expect(htmlPreview).toContainText('button');

    // Step 9: CSSプレビューが表示されていることを確認
    const cssPreview = page.locator('[data-testid="css-preview"]');
    await expect(cssPreview).toBeVisible();

    // Step 10: 保存ボタンをクリック
    const saveButton = page.locator('button:has-text("保存する")');
    await saveButton.click();

    // Step 11: 保存成功メッセージが表示されることを確認
    const successMessage = page.locator('[role="alert"]:has-text("保存しました")');
    await expect(successMessage).toBeVisible();

    // Step 12: タグが管理画面に表示されることを確認
    await page.goto('/custom-tags');
    const tagItem = page.locator('text=my-button');
    await expect(tagItem).toBeVisible();
  });

  test('エラー系: 不正なタグ名を入力した場合', async ({ page }) => {
    // Step 1: プロンプトを入力
    const promptInput = page.locator('textarea[name="prompt"]');
    await promptInput.fill('テスト');

    // Step 2: 不正なタグ名を入力
    const tagNameInput = page.locator('input[name="tagName"]');
    await tagNameInput.fill('');  // 空のタグ名

    // Step 3: 生成ボタンをクリック
    const generateButton = page.locator('button:has-text("生成する")');
    await generateButton.click();

    // Step 4: エラーメッセージが表示されることを確認
    const errorMessage = page.locator('[role="alert"]:has-text("タグ名は必須です")');
    await expect(errorMessage).toBeVisible();
  });

  test('バリデーション: XSS脆弱性を含むHTMLを入力した場合', async ({ page }) => {
    // Step 1: プロンプトを入力
    const promptInput = page.locator('textarea[name="prompt"]');
    await promptInput.fill('スクリプトタグを含むHTML');

    // Step 2: タグ名を入力
    const tagNameInput = page.locator('input[name="tagName"]');
    await tagNameInput.fill('xss-test');

    // Step 3: 生成ボタンをクリック
    const generateButton = page.locator('button:has-text("生成する")');
    await generateButton.click();

    // Step 4: 生成完了を待つ
    const loadingIndicator = page.locator('[data-testid="loading-indicator"]');
    await expect(loadingIndicator).not.toBeVisible({ timeout: 15000 });

    // Step 5: バリデーションボタンをクリック
    const validateButton = page.locator('button:has-text("検証する")');
    if (await validateButton.isVisible()) {
      await validateButton.click();

      // Step 6: 検証エラーが表示されることを確認
      const validationError = page.locator('[data-testid="validation-error"]');
      await expect(validationError).toBeVisible();
    }
  });

  test('レスポンシブテスト: モバイルデバイスでの操作', async ({ page }) => {
    // ビューポートをモバイルサイズに変更
    await page.setViewportSize({ width: 375, height: 667 });

    // Step 1: プロンプト入力フィールドが表示されていることを確認
    const promptInput = page.locator('textarea[name="prompt"]');
    await expect(promptInput).toBeVisible();

    // Step 2: プロンプトを入力
    await promptInput.fill('モバイル用ボタン');

    // Step 3: タグ名を入力
    const tagNameInput = page.locator('input[name="tagName"]');
    await tagNameInput.fill('mobile-btn');

    // Step 4: 生成ボタンをクリック
    const generateButton = page.locator('button:has-text("生成する")');
    await generateButton.click();

    // Step 5: 生成完了を待つ
    const loadingIndicator = page.locator('[data-testid="loading-indicator"]');
    await expect(loadingIndicator).not.toBeVisible({ timeout: 15000 });

    // Step 6: プレビューが表示されることを確認
    const htmlPreview = page.locator('[data-testid="html-preview"]');
    await expect(htmlPreview).toBeVisible();
  });

  test('テンプレート検索・クローン・カスタマイズ・保存フロー', async ({ page }) => {
    // Step 1: テンプレート管理ページに移動
    await page.goto('/custom-tags/templates');

    // Step 2: テンプレート検索フィールドが表示されていることを確認
    const searchInput = page.locator('input[placeholder*="検索"]');
    await expect(searchInput).toBeVisible();

    // Step 3: テンプレートを検索
    await searchInput.fill('ボタン');
    await page.waitForTimeout(500);

    // Step 4: 検索結果が表示されることを確認
    const templateItem = page.locator('[data-testid="template-item"]').first();
    await expect(templateItem).toBeVisible();

    // Step 5: クローンボタンをクリック
    const cloneButton = page.locator('button:has-text("クローン"):first-of-type');
    if (await cloneButton.isVisible()) {
      await cloneButton.click();

      // Step 6: クローン確認ダイアログが表示されることを確認
      const dialog = page.locator('role=dialog');
      await expect(dialog).toBeVisible();

      // Step 7: テンプレート名を入力
      const nameInput = page.locator('input[name="templateName"]');
      await nameInput.fill('カスタム化したボタン');

      // Step 8: 確認ボタンをクリック
      const confirmButton = page.locator('button:has-text("確認")');
      await confirmButton.click();

      // Step 9: 成功メッセージが表示されることを確認
      const successMessage = page.locator('[role="alert"]:has-text("クローンしました")');
      await expect(successMessage).toBeVisible();
    }
  });

  test('エラーハンドリング: Ollamaサーバーに接続できない場合', async ({ page }) => {
    // Note: この テストは、Ollama が実際に接続不可の場合のみ実行
    // Step 1: プロンプトを入力
    const promptInput = page.locator('textarea[name="prompt"]');
    await promptInput.fill('テスト');

    // Step 2: タグ名を入力
    const tagNameInput = page.locator('input[name="tagName"]');
    await tagNameInput.fill('error-test');

    // Step 3: 生成ボタンをクリック
    const generateButton = page.locator('button:has-text("生成する")');
    await generateButton.click();

    // Step 4: エラーメッセージが表示されることを確認
    const errorMessage = page.locator('[role="alert"][class*="error"]');
    await expect(errorMessage).toBeVisible({ timeout: 20000 });
  });
});
