import { test, expect, type Locator, type Page } from '@playwright/test';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, fetchAccessToken, loginAsAdmin } from './helpers';

/**
 * issue #753: このファイルは、独立ページとして存在しない `/custom-tags` と、ブラウザから
 * 無認証で叩ける前提の `/api/custom-tags/validate` を対象にした古いテストだった。
 * 現在の構成に合わせて次のように書き直している(計測している指標と閾値は元のまま維持する)。
 *
 *   - カスタムタグ生成UIはプロジェクト詳細の「タグ」ページ(/projects/{id}/tags)の
 *     「カスタムタグ管理」タブに埋め込まれている(custom-tag-generation.spec.ts と同じ導線)。
 *     このページは requireAdminSession() で保護されているため、helpers.ts の loginAsAdmin で
 *     Keycloak経由のログインを済ませてから遷移する。
 *   - 検証APIは gateway 経由の POST /api/custom-tags/validate(content-service の
 *     CustomTagController#validate)。ブラウザのfetchではなくPlaywrightのrequestフィクスチャから
 *     アクセストークン付きで呼ぶ(main-scenario.spec.ts と同じ方針)。レスポンスのフィールド名は
 *     `valid` ではなく `isValid`(services/content の ValidationResult)。
 *   - プロジェクトが1件も無い環境で無検証のままpassしないよう、UI側のテストは
 *     beforeEach でプロジェクトを1件作成し afterEach で削除する(issue #645、post-creation.spec.ts と同じ)。
 */

/** 「カスタムタグ管理」タブ内のAI生成フォーム。同じ画面の下部にある手動追加フォームと区別する。 */
function generationFormOf(page: Page): Locator {
  return page.locator('form').filter({ has: page.locator('textarea[name="prompt"]') });
}

/** プロジェクト詳細の「タグ」ページを開き、「カスタムタグ管理」タブへ切り替える。 */
async function openCustomTagsTab(page: Page, projectId: number): Promise<void> {
  await page.goto(`/projects/${projectId}/tags`);
  await page.locator('button:has-text("カスタムタグ管理")').click();
}

test.describe('カスタムタグ検証APIのパフォーマンス', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  test('APIレスポンス時間が2秒以内であること', async ({ request }) => {
    const accessToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);

    const startTime = Date.now();
    const response = await request.post('/api/custom-tags/validate', {
      headers: { Authorization: `Bearer ${accessToken}` },
      data: { htmlTemplate: '<div>{{content}}</div>', cssContent: '.div { padding: 10px; }' },
    });
    const responseTime = Date.now() - startTime;

    expect(
      response.ok(),
      `検証APIの呼び出しに失敗しました (status=${response.status()}): ${await response.text()}`
    ).toBe(true);

    console.log(`API response time: ${responseTime}ms`);
    expect(responseTime).toBeLessThan(2000); // 2秒以内

    // レスポンスのフィールド名はisValid(旧テストのvalidは実装に存在しない)
    expect(await response.json()).toHaveProperty('isValid');
  });

  test('複数リクエストの並列処理パフォーマンス', async ({ request }) => {
    const accessToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);

    const startTime = Date.now();

    // 複数のバリデーションリクエストを並列実行する
    const responses = await Promise.all(
      Array.from({ length: 5 }, (_, index) =>
        request.post('/api/custom-tags/validate', {
          headers: { Authorization: `Bearer ${accessToken}` },
          data: {
            htmlTemplate: `<div id="test-${index}">{{content}}</div>`,
            cssContent: `.test-${index} { padding: 10px; }`,
          },
        })
      )
    );

    const totalTime = Date.now() - startTime;

    console.log(`Total time for 5 parallel requests: ${totalTime}ms`);
    console.log(`Average time per request: ${totalTime / 5}ms`);

    expect(responses).toHaveLength(5);
    for (const response of responses) {
      expect(
        response.ok(),
        `検証APIの並列呼び出しに失敗しました (status=${response.status()}): ${await response.text()}`
      ).toBe(true);
    }

    // 5つのリクエストが3秒以内に完了することを確認
    expect(totalTime).toBeLessThan(3000);
  });
});

test.describe('カスタムタグ生成UIのパフォーマンス', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  let fixtureProjectName = '';
  let fixtureProjectId = 0;

  test.beforeEach(async ({ page }) => {
    fixtureProjectName = '';
    fixtureProjectId = 0;
    await loginAsAdmin(page);

    // Fixture: 「カスタムタグ管理」タブを確実に開けるよう、専用のプロジェクトを1件作る。
    const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const name = `E2E Perf Project ${unique}`;
    await page.goto('/projects');
    await page.locator('#project-form input[name="name"]').fill(name);
    await page.locator('#project-form input[name="slug"]').fill(`e2e-perf-${unique}`);
    await page.locator('#project-form button:has-text("作成")').click();
    await expect(page.getByText('作成しました。')).toBeVisible({ timeout: 10000 });
    fixtureProjectName = name;

    await page.goto('/projects');
    await page.locator(`tbody tr:has-text("${name}")`).locator('a:has-text("詳細")').click();
    await expect(page).toHaveURL(/\/projects\/\d+$/, { timeout: 10000 });
    fixtureProjectId = Number(/\/projects\/(\d+)$/.exec(page.url())![1]);
  });

  test.afterEach(async ({ page }) => {
    if (!fixtureProjectName) {
      return;
    }
    await page.goto('/projects');
    const row = page.locator(`tbody tr:has-text("${fixtureProjectName}")`);
    if ((await row.count()) === 0) {
      return;
    }
    await row.locator('a:has-text("詳細")').click();
    await expect(page).toHaveURL(/\/projects\/\d+$/, { timeout: 10000 });

    page.once('dialog', (dialog) => dialog.accept());
    await page.locator('button:has-text("プロジェクトを削除")').click();
    await expect(page).toHaveURL(/\/projects$/, { timeout: 10000 });
  });

  test('Ollamaレスポンス時間が10秒以内であること', async ({ page }) => {
    // 生成はOllamaの応答待ちを含むため、既定の30秒では不足しうる。
    test.setTimeout(90_000);

    await openCustomTagsTab(page, fixtureProjectId);
    const form = generationFormOf(page);
    await expect(form.locator('textarea[name="prompt"]')).toBeVisible();

    await form.locator('textarea[name="prompt"]').fill('シンプルなボタンコンポーネント');
    await form.locator('input[name="tagName"]').fill(`e2e-perf-button-${Date.now()}`);

    // レスポンスタイムを測定する
    const startTime = Date.now();
    await form.locator('button:has-text("生成")').click();

    // 「生成中...」の解除は成功表示(生成完了!)かフォーム内のエラー表示のいずれかで判定する
    // (旧テストが待っていた data-testid="loading-indicator" は実装に存在しない)。
    const successHeading = page.getByText('生成完了！');
    const errorMessage = form.locator('p.text-red-600');
    await expect(successHeading.or(errorMessage)).toBeVisible({ timeout: 60000 });

    const responseTime = Date.now() - startTime;
    console.log(`Ollama response time: ${responseTime}ms`);

    // OllamaがE2E実行環境に存在しない場合、計測値は生成時間ではなくエラー検出時間になるため
    // 性能判定の対象にしない(暗黙にpassさせず明示的にスキップする)。
    test.skip(!(await successHeading.isVisible()), 'Ollamaでの生成に失敗したため性能判定をスキップ');

    expect(responseTime).toBeLessThan(10000); // 10秒以内
  });

  test('UIレンダリング性能: タグ画面のページロード時間', async ({ page }) => {
    // Note: Lighthouse スコアの測定はブラウザのdevtoolsが必要なため、
    // ここではページロード時間と主要要素の表示を確認する。
    const tagsUrl = `/projects/${fixtureProjectId}/tags`;

    // 1回目の遷移はNext.js(devモード)のルートコンパイルを含むため、ウォームアップとして計測しない。
    await page.goto(tagsUrl);

    const navigationStart = Date.now();
    await page.goto(tagsUrl);
    const pageLoadTime = Date.now() - navigationStart;

    console.log(`Page load time: ${pageLoadTime}ms`);

    // ページロード時間が3秒以内であることを確認
    expect(pageLoadTime).toBeLessThan(3000);

    // 主要要素がすべて表示されていることを確認
    await page.locator('button:has-text("カスタムタグ管理")').click();
    const form = generationFormOf(page);
    await expect(form.locator('textarea[name="prompt"]')).toBeVisible();
    await expect(form.locator('input[name="tagName"]')).toBeVisible();
    await expect(form.locator('button:has-text("生成")')).toBeVisible();
  });
});
