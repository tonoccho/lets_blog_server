import { test, expect } from '@playwright/test';
import { E2E_ADMIN_PASSWORD, loginAsAdmin } from './helpers';

/**
 * issue #645: このファイルの大半のテストは `if (要素が存在すれば) { assert }` という形で
 * 書かれており、サイトが1件も登録されていない環境では常に無検証のままpassしていた
 * (「接続テスト」の待機処理も`.catch(() => null)`/`.catch(() => {...})`で例外を握りつぶしていた)。
 * beforeAllでManagedWordPressサイト(外部のSSHホストを必要としない自己完結型のフィクスチャ、
 * SiteCreationPanel.tsx/ManagedWordPressForm.tsx参照)を1件だけ構築し、以降の各テストが
 * このフィクスチャサイトを前提とした確定的な検証を行うようにする(afterAllで削除する)。
 * /sitesページ自体はproxy.tsによりログイン必須(未ログインは/loginへリダイレクト)であり、
 * サイト削除(deleteSiteAction)はrequireAdminSession()で保護されているため、
 * ログインにはadmin権限を持つe2e-admin@letsblog.local(helpers.ts参照)を使う。
 * (registerSiteAction/createManagedWordPressSiteAction自体はgetSession()のみでrequireAdminSession()
 * までは要求しないが、admin権限はその上位互換なのでここでの選択に影響しない。actions.ts参照)。
 *
 * beforeAll/afterAllの内部waitはWordPressの自動構築・削除で数分かかりうるため、
 * Playwrightのデフォルトフックタイムアウト(30秒)を超える。test.setTimeout()で
 * 各フック自体のタイムアウトを明示的に延長している。
 *
 * issue #765: `beforeAll`は「ファイルにつき1回」ではなく「ワーカーにつき1回」実行される。
 * playwright.config.tsは`fullyParallel: true`かつローカルではワーカー数がPlaywrightの自動判定
 * (CPU数の半分)のため、既定設定ではこのdescribeのテストが複数ワーカーへ分配され、
 * その数だけManagedWordPressの自動構築が同時に走っていた(構築が競合してタイムアウトし、
 * 削除されない孤児サイトがlbs_project.sitesに溜まる)。serialモードにすると
 * describe内の全テストが1ワーカーで順に実行されるため、フィクスチャの構築・削除は1回だけになる。
 */

test.describe('Site Registration and Connection Flow', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');
  // ManagedWordPressフィクスチャをワーカーごとに重複構築しないための直列化(issue #765)。
  // main-scenario.spec.tsと同じ方針。他のspecファイルとの並列実行は従来どおり行われる。
  test.describe.configure({ mode: 'serial' });

  let fixtureSiteKey: string;
  let fixtureSiteName: string;

  test.beforeAll(async ({ browser }) => {
    // WordPressの自動構築は数分かかりうるため、このフック自体のタイムアウトを
    // デフォルトの30秒から延長する(下の240秒waitより十分大きい値)。
    test.setTimeout(300_000);

    const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    fixtureSiteKey = `e2efix-${unique}`;
    fixtureSiteName = `E2E Fixture Site ${unique}`;

    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    const page = await context.newPage();
    try {
      await loginAsAdmin(page);
      await page.goto('/sites');

      // Fixture: 常駐WordPressコンテナ上にサブディレクトリでWordPressを自動構築する
      // (外部SSHホストの用意が不要な、自己完結型のフィクスチャ)。
      await page.locator('id=site-creation').scrollIntoViewIfNeeded();
      await page.locator('button:has-text("WordPressを新規構築")').click();
      await page.locator('input[name="managedName"]').fill(fixtureSiteName);
      await page.locator('input[name="managedSiteKey"]').fill(fixtureSiteKey);
      await page.locator('input[name="managedTitle"]').fill(fixtureSiteName);
      await page.locator('input[name="managedAdminUser"]').fill('e2efixtureadmin');
      await page.locator('input[name="managedAdminEmail"]').fill('e2e-fixture-admin@letsblog.local');
      await page.locator('input[name="managedAdminPassword"]').fill('E2eFixture#Passw0rd1');

      // WordPressの自動構築は完了まで数分かかる場合がある(ManagedWordPressForm.tsx参照)。
      await page.locator('button:has-text("構築する")').click();
      await expect(page.getByText('構築しました。')).toBeVisible({ timeout: 240000 });
    } finally {
      await context.close();
    }
  });

  test.afterAll(async ({ browser }) => {
    // サイト削除(WordPressコンテナ・専用DBの削除を伴いうる)にも時間がかかりうるため延長する。
    test.setTimeout(180_000);

    if (!fixtureSiteKey) {
      // beforeAll自体が実行されていない(describeごとskip等)。後始末の対象が無い。
      return;
    }

    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    const page = await context.newPage();
    // 一覧にフィクスチャ行が実在し、実際に削除を試みたかどうか。
    // 「解放対象が無い(=何もしなくてよい)」と「削除を試みて失敗した(=孤児が残る)」を
    // 区別するために使う。beforeAllが『構築しました。』を確認できたかどうかでは判断しない
    // ——構築自体は成功していて確認待ちだけがタイムアウトした場合(issue #765のProblem 1)、
    // 孤児が最も残りやすいのに失敗として報告されなくなるため。
    let deletionAttempted = false;
    try {
      await loginAsAdmin(page);
      await page.goto('/sites');

      const fixtureRow = page.locator(`tr:has-text("${fixtureSiteKey}")`);
      if ((await fixtureRow.count()) === 0) {
        // 行が無い = サイトが作られていない(beforeAllが構築前に失敗した)。解放対象も無い。
        return;
      }

      deletionAttempted = true;
      page.once('dialog', (dialog) => dialog.accept());
      await fixtureRow.locator('button:has-text("削除")').click();
      // ManagedWordPressの削除はコンテナ内のファイル削除+専用DBのDROPを伴い、30秒では
      // 終わらないことがある(issue #765)。main-scenario.spec.tsと同じ60秒を与える。
      await expect(fixtureRow).toHaveCount(0, { timeout: 60000 });
    } catch (error) {
      // 削除しきれなかった場合、DB行に加えてManagedWordPressの実体(wordpressコンテナ内の
      // ファイルと専用DB)が残る。どのサイトが孤児になったかをログから特定できるようにする
      // (握りつぶすとe2efix-*が溜まり続け、後から原因を追えなくなる。issue #765)。
      console.error(
        `[E2E ORPHAN] site_key=${fixtureSiteKey} のフィクスチャサイトを削除できませんでした。` +
          'ManagedWordPressの実体(wordpressコンテナ内のファイル・専用DB)が残っている可能性があります。' +
          '`./scripts/e2e-cleanup-test-data.sh --yes` で解放してください。'
      );
      // 実在する行に対して削除を試みて失敗した = 孤児が残ったということなので、失敗として報告する。
      // 行に到達する前(ログイン・遷移)で落ちた場合は、その原因は各テスト側で既に失敗として
      // 報告されているため、ログのみに留めて二重に報告しない。
      if (deletionAttempted) {
        throw error;
      }
    } finally {
      await context.close();
    }
  });

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await page.goto('/sites');
  });

  test('Navigate to sites page and view site list', async ({ page }) => {
    // Step 1: Verify sites page is loaded
    const heading = page.locator('h1:has-text("サイト")');
    await expect(heading).toBeVisible();

    // Step 2: Verify site list table is visible
    const siteTable = page.locator('table');
    await expect(siteTable).toBeVisible();
  });

  test('Site creation form is accessible', async ({ page }) => {
    // Step 1: Scroll to site creation section
    await page.locator('id=site-creation').scrollIntoViewIfNeeded();

    // Step 2: Verify site creation form is visible
    const siteCreationForm = page.locator('id=site-creation');
    await expect(siteCreationForm).toBeVisible();

    // Step 3: The mode-toggle buttons ("既存サイトを登録"/"WordPressを新規構築") always exist
    const createButtons = page.locator(
      'button:has-text("既存サイトを登録"), button:has-text("WordPressを新規構築")'
    );
    expect(await createButtons.count()).toBe(2);
    await expect(createButtons.first()).toBeVisible();
  });

  test('Test site connection for the fixture site', async ({ page }) => {
    // Step 1: The fixture site guarantees a matching row exists
    const fixtureRow = page.locator(`tr:has-text("${fixtureSiteKey}")`);
    await expect(fixtureRow).toBeVisible();

    // Step 2: Click its "疎通確認" button (CheckConnectionButton.tsx)
    const testButton = fixtureRow.locator('button:has-text("疎通確認")');
    await testButton.click();

    // Step 3: Verify the check resolves to either SUCCESS or FAILED (deterministic either/or,
    // not silently swallowed)
    const successBadge = fixtureRow.getByText('SUCCESS', { exact: true });
    const failedBadge = fixtureRow.getByText('FAILED', { exact: true });
    await expect(successBadge.or(failedBadge)).toBeVisible({ timeout: 15000 });
  });

  test('Sites page displays connection status controls for the fixture site', async ({ page }) => {
    // Step 1: The fixture site guarantees a matching row exists
    const fixtureRow = page.locator(`tr:has-text("${fixtureSiteKey}")`);
    await expect(fixtureRow).toBeVisible();

    // Step 2: Its row has a connection-check control
    await expect(fixtureRow.locator('button:has-text("疎通確認")')).toBeVisible();
  });

  test('Search filters the site list down to the fixture site', async ({ page }) => {
    // Step 1: Search input always exists (SiteListTable.tsx)
    const searchInput = page.locator('input[placeholder*="検索"]');
    await expect(searchInput).toBeVisible();

    // Step 2: Search for the fixture site's key
    await searchInput.fill(fixtureSiteKey);

    // Step 3: Verify the table still renders and the fixture site is present
    const siteTable = page.locator('table');
    await expect(siteTable).toBeVisible();
    // ManagedWordPressのURLはサイトキーを部分文字列として含む(https://localhost/sites/<siteKey>)ため、
    // 部分一致ではサイトキー列とURL列の両方に一致してstrict mode違反になる。完全一致で1件に絞る。
    await expect(page.getByRole('cell', { name: fixtureSiteKey, exact: true })).toBeVisible();
  });
});
