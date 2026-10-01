import { When } from './fixtures';
import { expect } from '../support';
import { measureServerActionRoundTrip, recordResponseTime } from '../support/responseBudget';

/** ログイン(`app/login/actions.ts`)の Server Action の3秒予算シナリオ(issue #1477)のステップ定義。 */

When(
  /^自動遷移を止めた「\/login」で救済の「ログインを開始する」を押して Server Action の往復を計測する$/,
  async ({ page, ctx }) => {
    // 自動遷移(`signIn("keycloak")`)の POST を止める。止めないと3秒を待たずにKeycloakへ遷移してしまい、
    // 救済のボタンが現れない。
    await page.route('**/api/auth/signin/keycloak', (route) => route.abort());
    await page.goto('/login');
    const start = page.getByTestId('nojs-login-submit');
    // 救済のフォームは3秒後にクライアントが描画する(= ハイドレーション済み)。
    await expect(start).toBeVisible({ timeout: 30_000 });
    const timing = await measureServerActionRoundTrip(page, async () => {
      await start.click();
      // Server Action が redirect() でKeycloakの認可エンドポイントへ飛ばす。
      await page.waitForURL(/\/realms\//, { timeout: 30_000, waitUntil: 'commit' });
    });
    recordResponseTime(ctx, timing.roundTripMs, 'ログイン開始(Server Action)の往復');
  }
);
