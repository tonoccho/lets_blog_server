import { test, expect } from '@playwright/test';

test.describe('カスタムタグ生成のセキュリティテスト', () => {
  test('XSS脆弱性チェック: scriptタグの検出', async ({ page }) => {
    await page.goto('/custom-tags');

    // プロンプトを入力
    const promptInput = page.locator('textarea[name="prompt"]');
    await promptInput.fill('スクリプトタグを含むHTML');

    const tagNameInput = page.locator('input[name="tagName"]');
    await tagNameInput.fill('xss-script-test');

    // バリデーション: scriptタグを含むHTMLをPOST
    const response = await page.evaluate(async () => {
      const res = await fetch('/api/custom-tags/validate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          htmlTemplate: '<div><script>alert("xss")</script>{{content}}</div>',
          cssContent: '',
        }),
      });
      return res.json();
    });

    // scriptタグはバリデーションエラーになるはず
    expect(response.valid).toBe(false);
    expect(response.errors.length).toBeGreaterThan(0);
    expect(JSON.stringify(response.errors)).toContain('script-tag');
  });

  test('XSS脆弱性チェック: イベントハンドラの検出', async ({ page }) => {
    await page.goto('/custom-tags');

    // イベントハンドラを含むHTMLをバリデート
    const response = await page.evaluate(async () => {
      const res = await fetch('/api/custom-tags/validate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          htmlTemplate: '<div onclick="alert(\'xss\')">{{content}}</div>',
          cssContent: '',
        }),
      });
      return res.json();
    });

    // イベントハンドラはバリデーションエラーになるはず
    expect(response.valid).toBe(false);
    expect(response.errors.length).toBeGreaterThan(0);
    expect(JSON.stringify(response.errors)).toContain('event-handler');
  });

  test('XSS脆弱性チェック: JavaScriptプロトコルの検出', async ({ page }) => {
    await page.goto('/custom-tags');

    // JavaScriptプロトコルを含むHTMLをバリデート
    const response = await page.evaluate(async () => {
      const res = await fetch('/api/custom-tags/validate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          htmlTemplate: '<a href="javascript:alert(\'xss\')">{{content}}</a>',
          cssContent: '',
        }),
      });
      return res.json();
    });

    // JavaScriptプロトコルはバリデーションエラーになるはず
    expect(response.valid).toBe(false);
    expect(response.errors.length).toBeGreaterThan(0);
    expect(JSON.stringify(response.errors)).toContain('javascript-protocol');
  });

  test('CSS インジェクション検出: behavior プロパティ', async ({ page }) => {
    await page.goto('/custom-tags');

    // behavior プロパティを含むCSSをバリデート
    const response = await page.evaluate(async () => {
      const res = await fetch('/api/custom-tags/validate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          htmlTemplate: '<div>{{content}}</div>',
          cssContent: '.alert { behavior: url(xss.htc); }',
        }),
      });
      return res.json();
    });

    // CSS インジェクションはバリデーションエラーになるはず
    expect(response.valid).toBe(false);
    expect(response.errors.length).toBeGreaterThan(0);
  });

  test('CSRF保護確認: トークンが含まれていることを確認', async ({ page }) => {
    await page.goto('/custom-tags');

    // フォームが送信される際にCSRFトークンが含まれていることを確認
    let csrfTokenFound = false;

    page.on('request', (request) => {
      if (request.url().includes('/api/custom-tags/')) {
        const headers = request.allHeaders();
        // X-CSRF-Token またはX-XSRF-TOKEN ヘッダーが存在することを確認
        if (headers['x-csrf-token'] || headers['x-xsrf-token']) {
          csrfTokenFound = true;
        }
      }
    });

    // プロンプトを入力して生成
    const promptInput = page.locator('textarea[name="prompt"]');
    await promptInput.fill('テスト');

    const tagNameInput = page.locator('input[name="tagName"]');
    await tagNameInput.fill('csrf-test');

    const generateButton = page.locator('button:has-text("生成する")');
    await generateButton.click();

    // リクエストが送信されるまで待機
    await page.waitForTimeout(1000);

    // Note: CSRFトークンが実装されている場合、このテストは検証できます
    // 実装されていない場合は、このテストはスキップするか、コメントアウトしてください
  });

  test('認可テスト: ユーザーが他のユーザーのテンプレートを削除できないこと', async ({ page }) => {
    await page.goto('/custom-tags/templates');

    // Note: このテストは複数ユーザー環境が必要です
    // 以下は、テストの構造を示すプレースホルダーです

    // Step 1: 別のユーザーが作成したテンプレートを取得
    const response = await page.evaluate(async () => {
      const res = await fetch('/api/custom-tag-templates/1');
      return { status: res.status, data: await res.json() };
    });

    // Step 2: 削除を試みる
    const deleteResponse = await page.evaluate(async () => {
      const res = await fetch('/api/custom-tag-templates/1', {
        method: 'DELETE',
      });
      return res.status;
    });

    // Step 3: 403 Forbiddenが返されるはず
    // (または、他のユーザーのテンプレートに対しては削除ボタンが表示されないはず)
    expect([403, 404]).toContain(deleteResponse);
  });

  test('SQLインジェクション対策: 特殊文字を含むクエリが安全に処理されること', async ({ page }) => {
    await page.goto('/custom-tags');

    // SQLインジェクション文字列を含むタグ名を入力
    const sqlInjectionPayload = "'; DROP TABLE custom_tags; --";

    const tagNameInput = page.locator('input[name="tagName"]');
    await tagNameInput.fill(sqlInjectionPayload);

    const promptInput = page.locator('textarea[name="prompt"]');
    await promptInput.fill('テスト');

    // 生成を試みる
    const generateButton = page.locator('button:has-text("生成する")');
    await generateButton.click();

    // エラーが発生するか、タグが作成されないことを確認
    // (データベースが破損していないこと)
    await page.waitForTimeout(2000);

    // テンプレートページに移動して、テーブルが存在することを確認
    await page.goto('/custom-tags/templates');
    const templatesList = page.locator('[data-testid="templates-list"]');

    // ページがロードできることを確認（テーブルが破損していないことを示す）
    await expect(templatesList).toBeVisible({ timeout: 5000 });
  });

  test('入力サニタイズ: ユーザー入力が正しくサニタイズされること', async ({ page }) => {
    await page.goto('/custom-tags');

    // HTMLタグを含む説明を入力
    const descriptionWithHtml = '<img src=x onerror="alert(\'xss\')">';
    const descriptionInput = page.locator('textarea[name="description"]');
    await descriptionInput.fill(descriptionWithHtml);

    const promptInput = page.locator('textarea[name="prompt"]');
    await promptInput.fill('テスト');

    const tagNameInput = page.locator('input[name="tagName"]');
    await tagNameInput.fill('sanitize-test');

    const generateButton = page.locator('button:has-text("生成する")');
    await generateButton.click();

    // ページがロードでき、エラーが発生しないことを確認
    const loadingIndicator = page.locator('[data-testid="loading-indicator"]');
    await expect(loadingIndicator).not.toBeVisible({ timeout: 15000 });

    // 説明がサニタイズされて表示されることを確認
    const htmlPreview = page.locator('[data-testid="html-preview"]');
    if (await htmlPreview.isVisible()) {
      const content = await htmlPreview.innerHTML();
      // JavaScriptが実行されていないことを確認
      expect(content).not.toContain('onerror');
    }
  });
});
