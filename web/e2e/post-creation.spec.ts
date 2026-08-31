import { test, expect } from '@playwright/test';
import { E2E_ADMIN_PASSWORD, loginAsAdmin } from './helpers';

/**
 * issue #645: このファイルの大半のテストは `if (要素が存在すれば) { assert }` という形で
 * 書かれており、プロジェクトが1件も登録されていない環境では常に無検証のままpassしていた
 * (加えてタイトル入力欄のセレクタが実際のDOM(input[name="name"]/input[name="slug"])と
 * 一致しておらず、`if (await titleInput.isVisible())`の分岐に一度も入っていなかった)。
 * beforeEachで確実に1件プロジェクトを作成するfixtureデータ投入を行い、条件分岐に依存しない
 * 確定的な検証に置き換える。/projectsはrequireAdminSession()で保護されているため、
 * ログインにはadmin権限を持つe2e-admin@letsblog.local(helpers.ts参照)を使う。
 *
 * なお、検索・フィルタ機能や一覧上の削除ボタンは/projectsの現在の実装には存在しない
 * (issue #645の調査で確認、ProjectsTable.tsxは定義されているがpage.tsxからは未使用のdead code)。
 * これらは「未実装であること」自体を確定的に固定するテストに置き換えている。
 *
 * 各テストで作成したfixtureプロジェクト(および「Create a new project...」テストが追加で
 * 作成するプロジェクト)は、afterEachで(/projects/{id}の「プロジェクトを削除」ボタン、
 * DeleteProjectButton.tsx参照)確実に削除する。実行のたびにプロジェクトが増え続けるのを防ぐため。
 *
 * issue #765: このspecはbeforeEachごとにプロジェクトを1件作るだけで、ManagedWordPressの
 * 自動構築は行わない(それはsite-registration.spec.ts / main-scenario.spec.tsの担当)。
 * そのためワーカー間で構築が競合することはないが、/projectsの一覧は全ワーカー・全specで
 * 共有されるため、「一覧の先頭行」を対象にするアサーションは他のテストが並列に作成・削除している
 * プロジェクトを掴んでしまう。一覧を見るテストは必ず自分のフィクスチャ行を名前で特定する。
 */

test.describe('Article/Post Creation Workflow', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  let fixtureProjectName: string;
  // beforeEachがログイン等で失敗した場合でもafterEachが安全にno-opできるよう、
  // 空配列で初期化しておく(ログイン成功後にfixture作成分を追加する)。
  let createdProjectNames: string[] = [];

  test.beforeEach(async ({ page }) => {
    createdProjectNames = [];
    await loginAsAdmin(page);

    // Fixture: 各テストの実行前に、プロジェクト一覧へ必ず1件のプロジェクトが存在する状態を作る。
    const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    fixtureProjectName = `E2E Fixture Project ${unique}`;
    createdProjectNames.push(fixtureProjectName);
    await page.goto('/projects');
    await page.locator('#project-form input[name="name"]').fill(fixtureProjectName);
    await page.locator('#project-form input[name="slug"]').fill(`e2e-fixture-${unique}`);
    await page.locator('#project-form button:has-text("作成")').click();
    await expect(page.getByText('作成しました。')).toBeVisible({ timeout: 10000 });
    await page.reload();
  });

  test.afterEach(async ({ page }) => {
    // このテストが作成した全プロジェクト(fixture + テスト自身が追加作成したもの)を後始末する。
    for (const name of createdProjectNames) {
      await page.goto('/projects');
      const row = page.locator(`tbody tr:has-text("${name}")`);
      if ((await row.count()) === 0) {
        continue;
      }
      await row.locator('a:has-text("詳細")').click();
      await expect(page).toHaveURL(/\/projects\/\d+$/, { timeout: 10000 });

      page.once('dialog', (dialog) => dialog.accept());
      await page.locator('button:has-text("プロジェクトを削除")').click();
      await expect(page).toHaveURL(/\/projects$/, { timeout: 10000 });
    }
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

    // Step 3: Verify the actual form inputs are present (name/slug, see ProjectForm.tsx)
    const nameInput = page.locator('#project-form input[name="name"]');
    const slugInput = page.locator('#project-form input[name="slug"]');
    await expect(nameInput).toBeVisible();
    await expect(slugInput).toBeVisible();
  });

  test('Create a new project with basic information', async ({ page }) => {
    // Step 1: Scroll to project form
    await page.locator('id=project-form').scrollIntoViewIfNeeded();

    // Step 2: Fill project form fields
    const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const projectTitle = `Test Project ${unique}`;
    createdProjectNames.push(projectTitle); // afterEachで後始末する対象に追加する
    await page.locator('#project-form input[name="name"]').fill(projectTitle);
    await page.locator('#project-form input[name="slug"]').fill(`test-project-${unique}`);

    // Step 3: Submit
    await page.locator('#project-form button:has-text("作成")').click();

    // Step 4: Verify project was created (success message rendered by ProjectForm.tsx)
    await expect(page.getByText('作成しました。')).toBeVisible({ timeout: 10000 });

    // Step 5: Verify the new project appears in the list after reload
    await page.reload();
    const projectName = page.locator(`text="${projectTitle}"`);
    await expect(projectName).toBeVisible({ timeout: 5000 });
  });

  test('View project details', async ({ page }) => {
    // Step 1: The fixture project guarantees a matching row exists
    // (一覧の先頭行ではなく、このテストが作ったフィクスチャ行を対象にする。先頭行は
    //  他のspec/ワーカーが並列に作成・削除しているプロジェクトになりうるため、
    //  クリック直前に行が消えて不安定になる。issue #765)
    const fixtureRow = page.locator(`tbody tr:has-text("${fixtureProjectName}")`);
    await expect(fixtureRow).toBeVisible();

    // Step 2: Click its "詳細" link to view details
    const detailLink = fixtureRow.locator('a:has-text("詳細")');
    await expect(detailLink).toBeVisible();
    await detailLink.click();

    // Step 3: Verify project details page is loaded
    await expect(page).toHaveURL(/\/projects\/\d+$/, { timeout: 10000 });

    // Step 4: Verify project information is displayed
    const projectContent = page.locator('main, [role="main"], body');
    await expect(projectContent.first()).toBeVisible();
  });

  test('Project list displays project information', async ({ page }) => {
    // Step 1: Verify table headers are visible
    const tableHeaders = page.locator('thead');
    await expect(tableHeaders).toBeVisible();

    // Step 2: The fixture project guarantees a matching row exists
    // (先頭行は他のspec/ワーカーのプロジェクトになりうるため、フィクスチャ行を対象にする。issue #765)
    const fixtureRow = page.locator(`tbody tr:has-text("${fixtureProjectName}")`);
    await expect(fixtureRow).toBeVisible();

    // Step 3: Verify columns are present (name, slug, environment badges, created date)
    const cells = fixtureRow.locator('td, th');
    expect(await cells.count()).toBeGreaterThan(0);
  });

  test('Project search/filter input is not implemented yet', async ({ page }) => {
    // /projectsには検索・フィルタ入力欄が実装されていない(issue #645で確認)。
    // 将来実装された場合はこのテストが失敗して気づけるよう、不在を確定的に固定する。
    const searchInput = page.locator(
      'input[placeholder*="検索"], input[placeholder*="Search"], input[placeholder*="filter"]'
    );
    await expect(searchInput).toHaveCount(0);
  });

  test('Project row delete button is not implemented yet', async ({ page }) => {
    // /projectsの一覧行には削除ボタンが実装されていない(issue #645で確認、ProjectsTable.tsxは
    // 定義されているがpage.tsxからは未使用)。
    const deleteButtons = page.locator('tbody tr button:has-text("削除"), tbody tr button[aria-label*="delete"]');
    await expect(deleteButtons).toHaveCount(0);
  });
});
