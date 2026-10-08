import { When, Then } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken, loginAsAdmin } from '../support';
import { clickUntilDone, withDialogAccepted } from '../support/retryClick';

/**
 * `/users` の「削除」ボタン経由の削除(issue #1383)。検証用メンバーの用意と後片付けは
 * `userManagement.steps.ts` の既存ステップ(`ctx.userManagementEmail` / `ctx.userManagementUserId`)を使う。
 */

When('管理者がユーザー一覧でそのメンバーの行の「削除」を押して確認を承認する', async ({ page, ctx }) => {
  const email = ctx.userManagementEmail as string;
  await loginAsAdmin(page);
  await page.goto('/users');
  const row = page.locator('tr', { hasText: email });
  await expect(row).toBeVisible();
  // ハイドレーション未完了のクリック空振りに備え、行が消えるまでクリックし直す(#1386と同じ手当て)。
  // 手動リロードはしない: 行が消えるのはクライアント側の状態更新によることを確かめるため。
  await withDialogAccepted(page, () =>
    clickUntilDone(row.getByRole('button', { name: '削除' }), {
      isDone: async () => (await row.count()) === 0,
      waitDone: (timeout) => row.waitFor({ state: 'detached', timeout }),
    })
  );
  ctx.userManagementDeleted = true;
});

Then('リロードなしでそのメンバーの行がユーザー一覧から消える', async ({ page, ctx }) => {
  const email = ctx.userManagementEmail as string;
  await expect(page.locator('tr', { hasText: email })).toHaveCount(0);
});

Then('そのメンバーはAPIでも取得できない', async ({ request, ctx }) => {
  const userId = ctx.userManagementUserId as number;
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const response = await request.get(`/api/users/${userId}`, { headers: { Authorization: `Bearer ${token}` } });
  expect(response.status(), `削除したはずのユーザー ${userId} がAPIで取得できます`).toBe(404);
});
