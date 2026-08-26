import { test, expect } from '@playwright/test';
import { loginViaKeycloak } from './helpers';

/**
 * issue #645: このファイルのほぼ全テストが `if (imageCount > 0) {...}` に包まれており、
 * 生成画像が1件も無い環境では常に無検証のままpassしていた。加えて、
 * `imageContainer.isClickable()`(Playwright Locatorに存在しないメソッド)や
 * `input[type="file"]`によるローカルファイルアップロードなど、/image-galleryの実際の実装
 * (src/app/image-gallery/page.tsx, ImageGalleryGrid.tsx)には存在しない機能を前提にしていた。
 * /image-galleryはAI生成画像(ComfyUI/ChatGPT)の一覧・詳細・タグ編集・削除のみを提供し、
 * ローカルファイルのアップロードや検索・ページネーションは実装されていない。
 *
 * beforeAllで実際にプロジェクトを作成し、アセット画像生成パネル(ProjectAssetGenerationPanel.tsx、
 * ComfyUI経由)で1枚だけ画像を生成する。生成に成功した画像はlegacy-apiの
 * AiAssistService#generateImageがMediaGeneratedImageClient経由でmedia-serviceへ保存するため、
 * /image-galleryへ確実に1件表示される状態になる。ComfyUIが利用できない実行環境では、
 * custom-tag-generation.spec.tsと同じ方針でtest.skipにより明示的にスキップする
 * (暗黙のvacuous passにはしない)。
 *
 * 削除テストがこのフィクスチャ画像自体を削除するため、フルパラレル実行時に他のテストと
 * 競合しないようこのdescribe全体をserialモードで実行する。
 *
 * ComfyUI生成は数分かかりうるため、beforeAll自体のタイムアウトをPlaywrightのデフォルト30秒から
 * 延長している(test.setTimeout())。また、画像のalt属性(prompt文字列)はlegacy-apiの
 * AiAssistService.resolveParams()でapp.default-quality-prompt(既定で
 * "high quality, highly detailed, sharp focus, masterpiece")が自動的に末尾へ連結されるため、
 * fixturePromptとの完全一致ではなく前方一致で照合する。
 */
const ADMIN_EMAIL = 'e2e-admin@letsblog.local';
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? '';

test.describe('Image Gallery Workflow', () => {
  test.describe.configure({ mode: 'serial' });
  test.skip(!ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  let fixturePrompt: string;
  let fixtureReady = false;
  let fixtureProjectId: string | null = null;

  test.beforeAll(async ({ browser }) => {
    // プロジェクト作成+ComfyUI生成(最大120秒待つ)を合わせて数分かかりうるため、
    // このフック自体のタイムアウトをデフォルトの30秒から延長する。
    test.setTimeout(200_000);

    const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    fixturePrompt = `E2E fixture image ${unique}`;

    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    const page = await context.newPage();
    try {
      await loginViaKeycloak(page, ADMIN_EMAIL, ADMIN_PASSWORD);

      // Fixture: 生成画像の土台となるプロジェクトを作成する。
      const projectName = `E2E Image Fixture Project ${unique}`;
      await page.goto('/projects');
      await page.locator('#project-form input[name="name"]').fill(projectName);
      await page.locator('#project-form input[name="slug"]').fill(`e2e-image-fixture-${unique}`);
      await page.locator('#project-form button:has-text("作成")').click();
      await expect(page.getByText('作成しました。')).toBeVisible({ timeout: 10000 });
      await page.reload();
      await page.locator(`tbody tr:has-text("${projectName}") a:has-text("詳細")`).click();
      await expect(page).toHaveURL(/\/projects\/\d+$/, { timeout: 10000 });
      fixtureProjectId = page.url().match(/\/projects\/(\d+)$/)?.[1] ?? null;

      // アセット画像生成パネルを開き、低steps・小サイズ・1枚のみの最小構成で生成する。
      await page.locator('button:has-text("アセット画像生成")').click();
      await page.locator('textarea[placeholder="生成したい画像の説明"]').fill(fixturePrompt);
      await page.locator('label:has-text("steps") + input').fill('5');
      await page.locator('label:has-text("width") + input').fill('512');
      await page.locator('label:has-text("height") + input').fill('512');
      await page.locator('label:has-text("batch size") + input').fill('1');

      const generateButton = page.getByRole('button', { name: '生成', exact: true });
      await generateButton.click();

      const successMessage = page.getByText('生成しました。アセットとして追加する画像を選択してください。');
      const errorMessage = page.locator('p.text-red-600');
      await expect(successMessage.or(errorMessage)).toBeVisible({ timeout: 120000 });
      fixtureReady = await successMessage.isVisible();
    } finally {
      await context.close();
    }
  });

  test.afterAll(async ({ browser }) => {
    test.setTimeout(60_000);

    if (!fixtureProjectId) {
      // beforeAllがプロジェクト作成の完了前に失敗した場合は、削除対象が存在しないため何もしない。
      return;
    }

    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    const page = await context.newPage();
    try {
      await loginViaKeycloak(page, ADMIN_EMAIL, ADMIN_PASSWORD);
      await page.goto(`/projects/${fixtureProjectId}`);

      page.once('dialog', (dialog) => dialog.accept());
      await page.locator('button:has-text("プロジェクトを削除")').click();
      await expect(page).toHaveURL(/\/projects$/, { timeout: 15000 });
    } finally {
      await context.close();
    }
  });

  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ADMIN_EMAIL, ADMIN_PASSWORD);
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

  test('Image gallery displays the fixture generated image', async ({ page }) => {
    test.skip(!fixtureReady, 'ComfyUIでの画像生成に失敗したため実行をスキップ');

    const fixtureImage = page.locator(`img[alt^="${fixturePrompt}"]`);
    await expect(fixtureImage).toBeVisible();
  });

  test('Image detail modal opens and shows generation parameters', async ({ page }) => {
    test.skip(!fixtureReady, 'ComfyUIでの画像生成に失敗したため実行をスキップ');

    const fixtureImage = page.locator(`img[alt^="${fixturePrompt}"]`);
    await fixtureImage.click();

    await expect(page.getByText('生成画像の詳細')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('steps')).toBeVisible();
  });

  test('Local file upload input is not implemented on the image gallery page', async ({ page }) => {
    // /image-galleryにファイルアップロード用のinput[type="file"]は実装されていない
    // (issue #645で確認。画像はComfyUI/ChatGPT生成、またはVSCode拡張経由でのみ登録される)。
    const fileInput = page.locator('input[type="file"]');
    await expect(fileInput).toHaveCount(0);
  });

  test('Search/filter input is not implemented on the image gallery page', async ({ page }) => {
    // 検索・フィルタ入力欄も実装されていない(issue #645で確認)。
    const searchInput = page.locator(
      'input[placeholder*="検索"], input[placeholder*="Search"], input[placeholder*="filter"]'
    );
    await expect(searchInput).toHaveCount(0);
  });

  test('Responsive layout on mobile', async ({ page }) => {
    // Step 1: Set mobile viewport
    await page.setViewportSize({ width: 375, height: 667 });

    // Step 2: Verify page is still accessible on mobile
    const heading = page.locator('h1');
    await expect(heading).toBeVisible();
  });

  test('Image deletion removes the fixture image from the gallery', async ({ page }) => {
    test.skip(!fixtureReady, 'ComfyUIでの画像生成に失敗したため実行をスキップ');

    const fixtureImage = page.locator(`img[alt^="${fixturePrompt}"]`);
    await fixtureImage.click();
    await expect(page.getByText('生成画像の詳細')).toBeVisible();

    page.once('dialog', (dialog) => dialog.accept());
    await page.locator('button:has-text("削除")').click();

    await expect(fixtureImage).toHaveCount(0, { timeout: 10000 });
  });
});
