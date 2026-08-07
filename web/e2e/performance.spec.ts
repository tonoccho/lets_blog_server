import { test, expect } from '@playwright/test';

test.describe('カスタムタグ生成のパフォーマンステスト', () => {
  test('Ollamaレスポンス時間が10秒以内であること', async ({ page }) => {
    await page.goto('/custom-tags');

    // プロンプトを入力
    const promptInput = page.locator('textarea[name="prompt"]');
    await promptInput.fill('シンプルなボタンコンポーネント');

    const tagNameInput = page.locator('input[name="tagName"]');
    await tagNameInput.fill('perf-test-button');

    // レスポンスタイムを測定
    const startTime = Date.now();

    // 生成ボタンをクリック
    const generateButton = page.locator('button:has-text("生成する")');
    await generateButton.click();

    // ローディングが完了するまで待機
    const loadingIndicator = page.locator('[data-testid="loading-indicator"]');
    await expect(loadingIndicator).not.toBeVisible({ timeout: 15000 });

    const endTime = Date.now();
    const responseTime = endTime - startTime;

    console.log(`Ollama response time: ${responseTime}ms`);
    expect(responseTime).toBeLessThan(10000); // 10秒以内
  });

  test('APIレスポンス時間が2秒以内であること', async ({ page }) => {
    await page.goto('/custom-tags');

    // 検証エンドポイントのレスポンス時間を測定
    const htmlContent = '<div>{{content}}</div>';
    const cssContent = '.div { padding: 10px; }';

    const startTime = performance.now();

    // バリデーションを実行
    const response = await page.evaluate(async ({ html, css }) => {
      const res = await fetch('/api/custom-tags/validate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ htmlTemplate: html, cssContent: css }),
      });
      return res.json();
    }, { html: htmlContent, css: cssContent });

    const endTime = performance.now();
    const responseTime = endTime - startTime;

    console.log(`API response time: ${responseTime}ms`);
    expect(responseTime).toBeLessThan(2000); // 2秒以内
    expect(response).toHaveProperty('valid');
  });

  test('UIレンダリング性能: Lighthouse スコア確認', async ({ page }) => {
    // Note: Lighthouse スコアの測定はブラウザのdevtoolsが必要
    // ここではページロード時間を確認する代わりのシンプルなテスト

    const navigationStart = performance.now();
    await page.goto('/custom-tags');
    const navigationEnd = performance.now();

    const pageLoadTime = navigationEnd - navigationStart;
    console.log(`Page load time: ${pageLoadTime}ms`);

    // ページロード時間が3秒以内であることを確認
    expect(pageLoadTime).toBeLessThan(3000);

    // 主要要素がすべて表示されていることを確認
    const promptInput = page.locator('textarea[name="prompt"]');
    const tagNameInput = page.locator('input[name="tagName"]');
    const generateButton = page.locator('button:has-text("生成する")');

    await expect(promptInput).toBeVisible();
    await expect(tagNameInput).toBeVisible();
    await expect(generateButton).toBeVisible();
  });

  test('複数リクエストの並列処理パフォーマンス', async ({ page }) => {
    await page.goto('/custom-tags');

    const startTime = Date.now();

    // 複数のバリデーションリクエストを並列実行
    const promises = [];
    for (let i = 0; i < 5; i++) {
      promises.push(
        page.evaluate(async (index) => {
          const res = await fetch('/api/custom-tags/validate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              htmlTemplate: `<div id="test-${index}">{{content}}</div>`,
              cssContent: `.test-${index} { padding: 10px; }`,
            }),
          });
          return res.json();
        }, i)
      );
    }

    const results = await Promise.all(promises);
    const endTime = Date.now();
    const totalTime = endTime - startTime;

    console.log(`Total time for 5 parallel requests: ${totalTime}ms`);
    console.log(`Average time per request: ${totalTime / 5}ms`);

    // 5つのリクエストが3秒以内に完了することを確認
    expect(totalTime).toBeLessThan(3000);
    expect(results).toHaveLength(5);
  });
});
