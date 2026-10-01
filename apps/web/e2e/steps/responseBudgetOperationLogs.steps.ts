import { When } from './fixtures';
import { expect } from '../support';
import { measureServerActionRoundTrip, recordResponseTime } from '../support/responseBudget';
import { waitForHydrated } from '../support/responseBudgetFixtures';

/** 操作ログ(`app/operation-logs/actions.ts`)の Server Action の3秒予算シナリオ(issue #1477)のステップ定義。 */

When('操作ログ画面で最初の操作の「コピー」を押して Server Action の往復を計測する', async ({ page, ctx }) => {
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  const copy = page.getByRole('button', { name: 'コピー', exact: true }).first();
  // 操作の記録は非同期(logging/async-path.feature)。ログインの直後は行が無いことがあるので、現れるまで開き直す。
  await expect(async () => {
    await page.goto('/operation-logs');
    await expect(copy).toBeVisible({ timeout: 5_000 });
  }).toPass({ timeout: 60_000 });
  await waitForHydrated(copy);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await copy.click();
    await expect(page.getByRole('button', { name: 'コピーしました' }).first()).toBeVisible({ timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, '操作トレースのコピー(Server Action)の往復');
});
