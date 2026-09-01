import { test, expect } from '@playwright/test';
import {
  E2E_ADMIN_PASSWORD,
  composeServiceControl,
  loginAsAdmin,
  waitForServicesHealthy,
} from './helpers';

/**
 * issue #588: サービス分割後は「下流サービスの1つが落ちていても、Webは全体が500にならず
 * 縮退表示になる」ことが要求される。実装上は各ページのサーバーコンポーネントが
 * apiClientの呼び出しを `.catch(() => [])` で受け、空リスト+空状態メッセージにフォールバック
 * する形になっている(src/app/posts/page.tsx, src/app/page.tsx等)。
 * その挙動が実際に効いているかを検証する。
 */
test.describe('サービス障害時の縮退表示', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  test('ダッシュボードの状態APIが落ちてもページは壊れず、直近の表示を維持する', async ({ page }) => {
    await loginAsAdmin(page);

    // ブラウザ側の定期更新(ポーリング/SSE)だけを落とす。初期表示はサーバー側で取得済みのため、
    // 「更新に失敗しても直前の表示を維持し、エラー画面にはならない」ことを検証できる
    // (ConnectedServiceStatusPanel.tsxの意図した挙動)。
    await page.route('**/api/dashboard/service-status', (route) =>
      route.fulfill({ status: 503, body: 'service unavailable' })
    );
    await page.route('**/api/dashboard/service-status/stream', (route) =>
      route.fulfill({ status: 503, body: 'service unavailable' })
    );

    await page.goto('/');

    await expect(page.locator('h1:has-text("ダッシュボード")')).toBeVisible();
    // グローバルエラーバウンダリ(app/error.tsx)が発火していないこと。
    await expect(page.getByText('エラーが発生しました')).toHaveCount(0);
    // 統計カードは表示され続ける(縮退してもナビゲーション可能な状態を保つ)。
    await expect(page.locator('a:has-text("登録サイト数")')).toBeVisible();
  });

  /**
   * 実際に下流サービスのコンテナを停止して縮退表示を検証する。docker composeのスタックを
   * 一時的に壊す操作を伴うため、明示的に E2E_ALLOW_SERVICE_DISRUPTION=1 を指定した場合のみ実行する
   * (テスト後にサービスを起動し直し、healthyになるまで待ってから終了する)。
   */
  test('content-serviceが停止していても投稿履歴ページは空状態で表示される', async ({ page }) => {
    test.skip(
      process.env.E2E_ALLOW_SERVICE_DISRUPTION !== '1',
      'E2E_ALLOW_SERVICE_DISRUPTION=1 が未設定のためスキップ(下流サービスを停止するテスト)'
    );
    // サービスの停止・再起動・healthy待ちを含むため長めに取る。
    test.setTimeout(300_000);

    await loginAsAdmin(page);

    try {
      composeServiceControl('stop', 'content');

      await page.goto('/posts');

      // ページ自体は描画される(500やエラーバウンダリにならない)。
      await expect(page.locator('h1:has-text("投稿履歴")')).toBeVisible();
      await expect(page.getByText('エラーが発生しました')).toHaveCount(0);
      // 取得できなかったデータは空リストとして縮退表示される。
      await expect(page.getByText('全0件を表示')).toBeVisible();
      await expect(
        page.getByText('投稿履歴はまだありません(VSCode拡張から投稿すると表示されます)')
      ).toBeVisible();
    } finally {
      composeServiceControl('start', 'content');
      waitForServicesHealthy(['content'], 180);
    }
  });
});
