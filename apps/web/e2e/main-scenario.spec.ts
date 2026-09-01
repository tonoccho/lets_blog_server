import { test, expect } from '@playwright/test';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  fetchAccessToken,
  loginAsAdmin,
} from './helpers';

/**
 * issue #588: マルチサービス構成における主要シナリオ
 * 「サイト登録 → 記事公開 → 履歴確認」を1本の通しテストで検証する。
 *
 * このシナリオは1回のテストで以下のサービスを横断する(単一サービスのユニット/結合テストでは
 * 検出できない、サービス間の結線の破綻を検出するのが目的):
 *   - Keycloak      : ホスト型ログイン画面でのサインイン、およびアクセストークンの発行
 *   - web(Next.js) : /sites のUI操作とサーバーアクション
 *   - gateway       : /api/** のルーティングとJWT検証
 *   - project       : サイト(lbs_project.sites)の登録
 *   - publishing    : POST /api/posts/publish によるWordPressへの記事公開
 *   - content       : 投稿履歴(lbs_content.posts)の記録と /posts での取得
 *   - wordpress     : ManagedWordPressとして自動構築される実際の公開先
 *
 * 記事公開はWeb UIには存在しない機能(VSCode拡張がgateway経由で
 * POST /api/posts/publish を呼ぶ)ため、その部分だけAPIを直接呼ぶ。トークンはブラウザ経由の
 * ログインと同じKeycloakユーザーで取得する(helpers.ts の fetchAccessToken 参照)。
 *
 * WordPressの自動構築に数分かかるため、テスト全体のタイムアウトを大きく取っている。
 * 生成物(サイト・投稿)はテスト内で削除し、削除しきれなかった場合も
 * scripts/e2e-cleanup-test-data.sh のプレフィックス(e2e*)に一致するため後から掃除できる。
 */
test.describe('主要シナリオ: サイト登録 → 記事公開 → 履歴確認', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');
  // フィクスチャの構築・公開・削除を順に行う単一テストのため、他プロジェクトとの
  // 並列実行は許容しつつ、テスト内部の順序は逐次であることを前提にする。
  test.describe.configure({ mode: 'serial' });

  test('サイトを登録して記事を公開すると投稿履歴に表示される', async ({ page, request }) => {
    // WordPress構築(最大240秒)+公開+後片付けの合計。既定の30秒では到底足りない。
    test.setTimeout(600_000);

    const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const siteKey = `e2emain-${unique}`;
    const siteName = `E2E Main Scenario Site ${unique}`;
    const postSlug = `e2e-main-post-${unique}`;
    const postTitle = `E2E Main Scenario Post ${unique}`;

    let wpPostId: string | null = null;
    let siteCreated = false;

    try {
      await test.step('1. Keycloak経由でログインする', async () => {
        await loginAsAdmin(page);
      });

      await test.step('2. ManagedWordPressサイトを登録する(project-service)', async () => {
        await page.goto('/sites');
        await page.locator('id=site-creation').scrollIntoViewIfNeeded();
        await page.locator('button:has-text("WordPressを新規構築")').click();
        await page.locator('input[name="managedName"]').fill(siteName);
        await page.locator('input[name="managedSiteKey"]').fill(siteKey);
        await page.locator('input[name="managedTitle"]').fill(siteName);
        await page.locator('input[name="managedAdminUser"]').fill('e2emainadmin');
        await page.locator('input[name="managedAdminEmail"]').fill('e2e-main-admin@letsblog.local');
        await page.locator('input[name="managedAdminPassword"]').fill('E2eMain#Passw0rd1');

        await page.locator('button:has-text("構築する")').click();
        await expect(page.getByText('構築しました。')).toBeVisible({ timeout: 240000 });
        siteCreated = true;

        // 登録直後に一覧へ反映されていることを確認する(サイト登録の完了条件)。
        // ManagedWordPressのURLはサイトキーを部分文字列として含む(https://localhost/sites/<siteKey>)ため、
        // 部分一致(td:has-text)ではサイトキー列とURL列の両方に一致してstrict mode違反になる。
        // サイトキー列のセルはテキストがサイトキーと完全一致するので、完全一致で1件に絞る。
        await page.goto('/sites');
        await expect(page.getByRole('cell', { name: siteKey, exact: true })).toBeVisible();
      });

      await test.step('3. gateway経由で記事を公開する(publishing-service)', async () => {
        const accessToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
        const response = await request.post('/api/posts/publish', {
          headers: { Authorization: `Bearer ${accessToken}` },
          multipart: {
            site: siteKey,
            title: postTitle,
            slug: postSlug,
            status: 'publish',
            markdown: `# ${postTitle}\n\nこれはE2E(issue #588)が投稿したテスト記事です。\n`,
          },
          timeout: 120000,
        });

        expect(
          response.ok(),
          `記事公開に失敗しました (status=${response.status()}): ${await response.text()}`
        ).toBe(true);

        const body = (await response.json()) as { wpPostId: string; status: string };
        expect(body.wpPostId).toBeTruthy();
        wpPostId = body.wpPostId;
      });

      await test.step('4. 投稿履歴に表示されることを確認する(content-service)', async () => {
        await page.goto('/posts');
        // 同じサイトに複数の投稿履歴が並ぶ可能性があるため、この実行専用でユニークなスラッグで
        // 行を1件に特定してから、サイト名とWP投稿IDが同じ行にあることを確認する。
        const historyRow = page.locator('tbody tr').filter({ hasText: postSlug });
        await expect(historyRow).toBeVisible({ timeout: 15000 });
        await expect(historyRow).toContainText(siteName);
        await expect(historyRow).toContainText(String(wpPostId));
      });
    } finally {
      // 後片付け: 投稿 → サイトの順に削除する(サイトを先に消すと投稿の削除先が失われるため)。
      if (wpPostId) {
        const accessToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD).catch(
          () => null
        );
        if (accessToken) {
          await request
            .delete(`/api/posts/${siteKey}/${wpPostId}`, {
              headers: { Authorization: `Bearer ${accessToken}` },
              timeout: 60000,
            })
            .catch(() => null);
        }
      }

      if (siteCreated) {
        await page.goto('/sites');
        const siteRow = page.locator(`tr:has-text("${siteKey}")`);
        if ((await siteRow.count()) > 0) {
          page.once('dialog', (dialog) => dialog.accept());
          await siteRow.locator('button:has-text("削除")').click();
          await expect(siteRow).toHaveCount(0, { timeout: 60000 });
        }
      }
    }
  });
});
