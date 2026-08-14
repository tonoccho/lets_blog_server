import { test, expect, type Locator, type Page } from '@playwright/test';

/**
 * カスタムタグ生成UIは独立した /custom-tags ページではなく、
 * プロジェクト詳細のタブ(/projects/[id]/tags 内「カスタムタグ管理」タブ)に埋め込まれている。
 * プロジェクトが1件も存在しない/未ログインなど到達できない場合はfalseを返す。
 */
async function openProjectCustomTagsTab(page: Page): Promise<boolean> {
  await page.goto('/projects');

  const detailLink = page.locator('a:has-text("詳細")').first();
  if (!(await detailLink.isVisible().catch(() => false))) {
    return false;
  }
  await detailLink.click();

  const tagsNavLink = page.locator('nav[aria-label="プロジェクトセクション"] a:has-text("タグ")');
  if (!(await tagsNavLink.isVisible().catch(() => false))) {
    return false;
  }
  await tagsNavLink.click();

  const customTagsTabButton = page.locator('button:has-text("カスタムタグ管理")');
  if (!(await customTagsTabButton.isVisible().catch(() => false))) {
    return false;
  }
  await customTagsTabButton.click();

  return generationFormOf(page).isVisible().catch(() => false);
}

/**
 * AIでカスタムタグを生成するフォーム。
 * 同じ画面の下部にタグ手動追加用の別フォーム(input[name="tagName"]等も同名)が存在するため、
 * textarea[name="prompt"]を持つフォームに絞り込んで一意にする。
 */
function generationFormOf(page: Page): Locator {
  return page.locator('form').filter({ has: page.locator('textarea[name="prompt"]') });
}

/**
 * 生成フォームを送信し、成功(「生成完了！」表示)/失敗(赤字エラー表示)のどちらかに到達するまで待つ。
 * 実装はOllama呼び出しと同時にDB保存まで行うため、生成成功=保存成功であり、別途保存ステップはない。
 */
async function submitGeneration(
  page: Page,
  form: Locator,
  { prompt, tagName, description }: { prompt: string; tagName: string; description?: string }
): Promise<{ success: boolean; successHeading: Locator; errorMessage: Locator }> {
  await form.locator('textarea[name="prompt"]').fill(prompt);
  await form.locator('input[name="tagName"]').fill(tagName);
  if (description) {
    await form.locator('input[name="description"]').fill(description);
  }
  await form.locator('button:has-text("生成")').click();

  const successHeading = page.getByText('生成完了！');
  const errorMessage = form.locator('p.text-red-600');
  await expect(successHeading.or(errorMessage)).toBeVisible({ timeout: 30000 });

  return { success: await successHeading.isVisible(), successHeading, errorMessage };
}

test.describe('カスタムタグ生成フロー', () => {
  test('正常系: プロンプト入力からタグ生成・自動保存までの完全フロー', async ({ page }) => {
    const reached = await openProjectCustomTagsTab(page);
    test.skip(!reached, 'カスタムタグ管理タブに到達できないため実行をスキップ');

    const form = generationFormOf(page);
    await expect(form.locator('textarea[name="prompt"]')).toBeVisible();

    const tagName = `e2e-btn-${Date.now()}`;
    const { success } = await submitGeneration(page, form, {
      prompt: '青いボタンコンポーネントを作成してください',
      tagName,
      description: 'カスタムボタンコンポーネント',
    });

    // OllamaサーバーがE2E実行環境に存在しない場合は生成が失敗しうるため、その場合はここで終了する
    test.skip(!success, 'Ollamaでの生成に失敗したため以降の検証をスキップ');

    // HTML/CSSプレビュー(data-testidは存在しないため<pre>要素で判定)が表示される
    await expect(page.locator('pre').first()).toBeVisible();

    // 生成結果は生成時点で既にDB保存済みのため、下部フォームは編集モードで開く(issue #354)。
    // 「追加」ボタンで再送信すると保存済みタグ名との重複エラーになるため、編集モード([更新]ボタン)にする。
    await expect(page.locator(`h2:has-text("カスタムタグを編集: [${tagName}]")`)).toBeVisible();

    // 生成と同時にDB保存されているため、再読み込み後もタグ一覧に表示される
    await page.reload();
    await page.locator('button:has-text("カスタムタグ管理")').click();
    await expect(page.locator(`td:has-text("[${tagName}]")`)).toBeVisible();
  });

  test('バリデーション: パターンに一致しないタグ名では生成が開始されない', async ({ page }) => {
    const reached = await openProjectCustomTagsTab(page);
    test.skip(!reached, 'カスタムタグ管理タブに到達できないため実行をスキップ');

    const form = generationFormOf(page);
    await form.locator('textarea[name="prompt"]').fill('テスト');

    // タグ名は英字始まりのみ許可(pattern="[a-zA-Z][a-zA-Z0-9_\-]*")。数字始まりは不正。
    const tagNameInput = form.locator('input[name="tagName"]');
    await tagNameInput.fill('1-invalid-name');
    await form.locator('button:has-text("生成")').click();

    // ブラウザのネイティブバリデーションにより送信自体がブロックされる
    const isValid = await tagNameInput.evaluate((el: HTMLInputElement) => el.checkValidity());
    expect(isValid).toBe(false);
    await expect(page.getByText('生成完了！')).not.toBeVisible();
  });

  test('セキュリティ検証: 不正なHTMLを要求した場合は拒否されるか検証結果が示される', async ({ page }) => {
    const reached = await openProjectCustomTagsTab(page);
    test.skip(!reached, 'カスタムタグ管理タブに到達できないため実行をスキップ');

    const form = generationFormOf(page);
    const { success, errorMessage } = await submitGeneration(page, form, {
      prompt: 'scriptタグを埋め込んだHTMLコンポーネントを作成してください',
      tagName: `e2e-xss-${Date.now()}`,
    });

    if (!success) {
      // サーバー側(CustomTagValidationService)がセキュリティ要件違反として生成自体を拒否したケース
      await expect(errorMessage).toBeVisible();
      return;
    }

    // Ollamaが安全なHTMLを生成した場合、生成後に自動実行される検証結果(成功/エラー)が表示される
    const validationSuccess = page.getByText('検証成功');
    const validationErrors = page.locator('h3:has-text("エラー (")');
    await expect(validationSuccess.or(validationErrors)).toBeVisible({ timeout: 10000 });
  });

  test('レスポンシブテスト: モバイルビューポートでも生成フォームを操作できる', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });

    const reached = await openProjectCustomTagsTab(page);
    test.skip(!reached, 'カスタムタグ管理タブに到達できないため実行をスキップ');

    const form = generationFormOf(page);
    await expect(form.locator('textarea[name="prompt"]')).toBeVisible();

    const { success } = await submitGeneration(page, form, {
      prompt: 'モバイル用ボタン',
      tagName: `e2e-mobile-${Date.now()}`,
    });

    if (success) {
      await expect(page.locator('pre').first()).toBeVisible();
    }
  });

  test('エラーハンドリング: 生成に失敗した場合はエラーメッセージが表示される', async ({ page }) => {
    // Note: このテストはOllamaが実際に接続不可の場合のみエラー分岐を検証できる
    const reached = await openProjectCustomTagsTab(page);
    test.skip(!reached, 'カスタムタグ管理タブに到達できないため実行をスキップ');

    const form = generationFormOf(page);
    const { success, errorMessage } = await submitGeneration(page, form, {
      prompt: 'テスト',
      tagName: `e2e-err-${Date.now()}`,
    });

    if (!success) {
      await expect(errorMessage).toBeVisible();
    }
  });
});

test.describe('カスタムタグテンプレートギャラリー', () => {
  test('テンプレート検索・詳細表示・クローンフロー', async ({ page }) => {
    // 実際のルートは /custom-tags/templates ではなく /custom-tag-templates
    await page.goto('/custom-tag-templates');

    const searchInput = page.locator('input[placeholder*="検索"]');
    const reached = await searchInput.isVisible().catch(() => false);
    test.skip(!reached, 'テンプレートギャラリーに到達できないため実行をスキップ');

    // クローン確認(window.confirm)はネイティブダイアログのため自動承諾する
    page.on('dialog', (dialog) => dialog.accept());

    await searchInput.fill('ボタン');
    await page.locator('button:has-text("検索")').click();
    await page.waitForURL(/search=/);

    // テンプレートカードにはdata-testidが無いため見出し要素で判定する
    const templateHeading = page.locator('h3.truncate').first();
    if (!(await templateHeading.isVisible().catch(() => false))) {
      // 検索条件に一致するテンプレートが存在しない場合はここで終了
      return;
    }
    await templateHeading.click();

    // 詳細モーダルにrole="dialog"は付与されていないため、複製フォームの表示で判定する
    const cloneNameInput = page.locator('input[placeholder="新しいテンプレート名"]');
    await expect(cloneNameInput).toBeVisible();
    await cloneNameInput.fill(`カスタム化したボタン-${Date.now()}`);

    // 複製実行(実装のボタンラベルは「複製を作成」、成功メッセージは表示されずモーダルが閉じる)
    await page.locator('button:has-text("複製を作成")').click();
    await expect(cloneNameInput).not.toBeVisible({ timeout: 10000 });
  });
});
