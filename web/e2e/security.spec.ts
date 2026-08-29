import { test, expect, type APIRequestContext, type Locator, type Page } from '@playwright/test';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  fetchAccessToken,
  loginAsAdmin,
} from './helpers';

/**
 * issue #753: このファイルは、独立ページとして存在しない `/custom-tags` /
 * `/custom-tags/templates` と、ブラウザから無認証で叩ける前提の
 * `/api/custom-tags/validate` を対象にした古いテストだった。検証している観点
 * (XSS/CSSインジェクション検出・CSRF保護・認可・SQLインジェクション耐性・入力サニタイズ)は
 * そのままに、現在の構成へ合わせて書き直している。
 *
 *   - 検証APIは gateway 経由の POST /api/custom-tags/validate(content-service の
 *     CustomTagController#validate)。PlaywrightのrequestフィクスチャからKeycloakの
 *     アクセストークン付きで呼ぶ(main-scenario.spec.ts と同じ方針)。
 *     レスポンスは `valid` ではなく `isValid`、エラー種別は CustomTagValidationService の
 *     `script-tag-detected` / `event-handler-detected` / `javascript-protocol-detected` /
 *     `css-injection-detected`。
 *   - カスタムタグ生成UIはプロジェクト詳細の「タグ」ページ(/projects/{id}/tags)の
 *     「カスタムタグ管理」タブ。requireAdminSession() で保護されているため
 *     helpers.ts の loginAsAdmin でログインしてから遷移する。
 *   - テンプレートギャラリーの実際のルートは `/custom-tag-templates`。
 */

/** 「カスタムタグ管理」タブ内のAI生成フォーム。同じ画面の手動追加フォームと区別する。 */
function generationFormOf(page: Page): Locator {
  return page.locator('form').filter({ has: page.locator('textarea[name="prompt"]') });
}

test.describe('カスタムタグ検証APIのセキュリティ', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  /** 検証APIを呼び、エラー種別の配列とともに結果を返す。 */
  async function validate(
    request: APIRequestContext,
    htmlTemplate: string,
    cssContent: string
  ): Promise<{ isValid: boolean; errorTypes: string[] }> {
    const accessToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
    const response = await request.post('/api/custom-tags/validate', {
      headers: { Authorization: `Bearer ${accessToken}` },
      data: { htmlTemplate, cssContent },
    });
    expect(
      response.ok(),
      `検証APIの呼び出しに失敗しました (status=${response.status()}): ${await response.text()}`
    ).toBe(true);

    const body = (await response.json()) as { isValid: boolean; errors: { type: string }[] };
    return { isValid: body.isValid, errorTypes: body.errors.map((e) => e.type) };
  }

  test('XSS脆弱性チェック: scriptタグの検出', async ({ request }) => {
    const { isValid, errorTypes } = await validate(
      request,
      '<div><script>alert("xss")</script>{{content}}</div>',
      ''
    );

    // scriptタグはバリデーションエラーになる
    expect(isValid).toBe(false);
    expect(errorTypes).toContain('script-tag-detected');
  });

  test('XSS脆弱性チェック: イベントハンドラの検出', async ({ request }) => {
    const { isValid, errorTypes } = await validate(
      request,
      '<div onclick="alert(\'xss\')">{{content}}</div>',
      ''
    );

    // on* 属性はバリデーションエラーになる
    expect(isValid).toBe(false);
    expect(errorTypes).toContain('event-handler-detected');
  });

  test('XSS脆弱性チェック: JavaScriptプロトコルの検出', async ({ request }) => {
    const { isValid, errorTypes } = await validate(
      request,
      '<a href="javascript:alert(\'xss\')">{{content}}</a>',
      ''
    );

    // javascript: プロトコルはバリデーションエラーになる
    expect(isValid).toBe(false);
    expect(errorTypes).toContain('javascript-protocol-detected');
  });

  test('CSS インジェクション検出: behavior プロパティ', async ({ request }) => {
    const { isValid, errorTypes } = await validate(
      request,
      '<div>{{content}}</div>',
      '.alert { behavior: url(xss.htc); }'
    );

    // IE固有のCSS機能(expression/behavior)はバリデーションエラーになる
    expect(isValid).toBe(false);
    expect(errorTypes).toContain('css-injection-detected');
  });
});

test.describe('カスタムタグテンプレートの認可', () => {
  test.skip(!E2E_TEST_PASSWORD, 'E2E_TEST_PASSWORDが未設定のためスキップ');

  test('認可テスト: 非adminユーザーはテンプレートを削除できないこと', async ({ request }) => {
    // 現在の実装(CustomTagTemplateService#delete)は削除をadmin権限に限定しており、
    // admin判定(AdminAuthorizationService#requireAdmin)は存在チェックより前に行われる。
    // したがって非adminのトークンでは、テンプレートの有無に関わらず削除されず403になる。
    const accessToken = await fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
    const response = await request.delete('/api/custom-tag-templates/1', {
      headers: { Authorization: `Bearer ${accessToken}` },
    });

    expect(response.status()).toBe(403);
  });
});

test.describe('Web UIのセキュリティ', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('CSRF保護確認: トークンが含まれていることを確認', async ({ page }) => {
    // 画面からのカスタムタグ操作はブラウザが直接 /api/custom-tags/* を叩くのではなく、
    // Next.jsのServer Action(app/custom-tags/actions.ts)経由で行われるため、
    // 旧テストが見ていた X-CSRF-Token ヘッダーはそもそも送出されない。
    // 現在のCSRF対策はNextAuthのCSRFトークン(ダブルサブミットCookie)であり、その存在を確認する。
    const response = await page.request.get('/api/auth/csrf');
    expect(response.ok()).toBe(true);

    const body = (await response.json()) as { csrfToken?: string };
    expect(body.csrfToken).toBeTruthy();

    // Cookie名はNEXTAUTH_URLがhttpsのとき __Host- が付く(next-authの既定動作)。
    const cookies = await page.context().cookies();
    const csrfCookie = cookies.find((cookie) => cookie.name.endsWith('next-auth.csrf-token'));
    expect(csrfCookie, 'NextAuthのCSRFトークンCookieが見つかりません').toBeDefined();
    expect(csrfCookie!.value).toBeTruthy();
    expect(csrfCookie!.httpOnly).toBe(true);
    expect(csrfCookie!.sameSite).toBe('Lax');
  });

  test('SQLインジェクション対策: 特殊文字を含むクエリが安全に処理されること', async ({ page }) => {
    // 生成フォームのタグ名は pattern="[a-zA-Z][a-zA-Z0-9_\-]*" でブラウザ側から拒否されるため、
    // 実際にDBへ文字列が渡る自由入力(テンプレートギャラリーの検索)で検証する。
    const sqlInjectionPayload = "'; DROP TABLE custom_tags; --";

    await page.goto('/custom-tag-templates');
    const searchInput = page.locator('input[placeholder*="検索"]');
    await expect(searchInput).toBeVisible();

    await searchInput.fill(sqlInjectionPayload);
    await page.locator('button:has-text("検索")').click();
    await page.waitForURL(/search=/);

    // 検索自体がエラーにならず、ページが正常に描画されること
    await expect(page.locator('h1:has-text("カスタムタグテンプレート")')).toBeVisible();

    // テーブルが破損していないこと(通常の一覧が引き続き取得できる)
    await page.goto('/custom-tag-templates');
    await expect(page.locator('h1:has-text("カスタムタグテンプレート")')).toBeVisible();
    await expect(page.locator('input[placeholder*="検索"]')).toBeVisible();
  });
});

test.describe('カスタムタグ生成の入力サニタイズ', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  let fixtureProjectName = '';
  let fixtureProjectId = 0;

  test.beforeEach(async ({ page }) => {
    fixtureProjectName = '';
    fixtureProjectId = 0;
    await loginAsAdmin(page);

    // Fixture: 「カスタムタグ管理」タブを確実に開けるよう、専用のプロジェクトを1件作る
    // (プロジェクトが無い環境で無検証のままpassしないようにする。issue #645)。
    const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const name = `E2E Security Project ${unique}`;
    await page.goto('/projects');
    await page.locator('#project-form input[name="name"]').fill(name);
    await page.locator('#project-form input[name="slug"]').fill(`e2e-sec-${unique}`);
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

  test('入力サニタイズ: ユーザー入力が正しくサニタイズされること', async ({ page }) => {
    // 生成はOllamaの応答待ちを含むため、既定の30秒では不足しうる。
    test.setTimeout(90_000);

    // XSSが成立した場合に確実に落とせるよう、ダイアログの発生自体を検出する。
    let dialogFired = false;
    page.on('dialog', (dialog) => {
      dialogFired = true;
      return dialog.dismiss();
    });

    await page.goto(`/projects/${fixtureProjectId}/tags`);
    await page.locator('button:has-text("カスタムタグ管理")').click();

    const form = generationFormOf(page);
    await expect(form.locator('textarea[name="prompt"]')).toBeVisible();

    await form.locator('textarea[name="prompt"]').fill('テスト');
    await form.locator('input[name="tagName"]').fill(`e2e-sanitize-${Date.now()}`);
    await form.locator('input[name="description"]').fill('<img src=x onerror="alert(\'xss\')">');
    await form.locator('button:has-text("生成")').click();

    const successHeading = page.getByText('生成完了！');
    const errorMessage = form.locator('p.text-red-600');
    await expect(successHeading.or(errorMessage)).toBeVisible({ timeout: 60000 });

    // OllamaがE2E実行環境に存在しない場合は生成結果が表示されないため、以降の検証はスキップする。
    test.skip(!(await successHeading.isVisible()), 'Ollamaでの生成に失敗したため以降の検証をスキップ');

    // 入力したHTMLがマークアップとして解釈されていないこと(要素化されていない)
    expect(await page.locator('img[onerror]').count()).toBe(0);

    // 生成結果のHTML/CSSプレビュー(実装は<pre>で表示、data-testidは無い)にも
    // イベントハンドラが混入していないこと
    const previewHtml = await page.locator('pre').first().innerHTML();
    expect(previewHtml).not.toContain('onerror');

    expect(dialogFired, 'スクリプトが実行されダイアログが表示されました').toBe(false);
  });
});
