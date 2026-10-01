import { Given, When } from './fixtures';
import { expect } from '../support';
import { adminHeaders, createThrowawayUser, waitForHydrated, type ThrowawayUser } from '../support/responseBudgetFixtures';
import { measureAndRecord, openLocation, projectId } from '../support/responseBudgetProject';

/**
 * プロジェクトのメンバー(`ProjectUserManager` / `AddProjectUserModal`)の Server Action の
 * 3秒予算シナリオ(issue #1477、`features/response-budget/server-action-project-members.feature`)の
 * ステップ定義。プロジェクトにサイトを紐付けない状態で測るので、WordPress 側のユーザー作成・同期は
 * 往復に含まれない(紐付く環境が無い旨が返る)。
 */

const MEMBER_KEY = 'responseBudgetMember';

function member(ctx: Record<string, unknown>): ThrowawayUser {
  return ctx[MEMBER_KEY] as ThrowawayUser;
}

Given('応答時間予算の検証用に、そのプロジェクトのメンバーではない使い捨ての利用者がいる', async ({ request, ctx }) => {
  ctx[MEMBER_KEY] = await createThrowawayUser(request, ctx);
});

Given('応答時間予算の検証用に、そのプロジェクトのメンバーである使い捨ての利用者がいる', async ({ request, ctx }) => {
  const user = await createThrowawayUser(request, ctx);
  ctx[MEMBER_KEY] = user;
  const response = await request.post(`/api/projects/${projectId(ctx)}/users`, {
    headers: await adminHeaders(request),
    data: { userId: user.id, wpRole: 'contributor' },
  });
  expect(response.ok(), `メンバーの追加に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
});

When('メンバータブで使い捨ての利用者を追加して Server Action の往復を計測する', async ({ page, ctx }) => {
  await openLocation(page, ctx, 'メンバー');
  const select = page.locator('select[name="userId"]');
  await expect(select).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(select);
  await select.selectOption({ label: member(ctx).email });
  await measureAndRecord(page, ctx, 'メンバーの追加', async () => {
    await page.getByRole('button', { name: '追加', exact: true }).click();
    await expect(page.getByText('追加しました。')).toBeVisible({ timeout: 30_000 });
  });
});

async function openMemberRow(page: import('@playwright/test').Page, ctx: Record<string, unknown>) {
  await openLocation(page, ctx, 'メンバー');
  const row = page.locator('tr', { hasText: member(ctx).email });
  await expect(row).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(row.getByRole('combobox'));
  return row;
}

When('メンバータブで使い捨ての利用者のロールを変更して Server Action の往復を計測する', async ({ page, ctx }) => {
  const row = await openMemberRow(page, ctx);
  await measureAndRecord(page, ctx, 'メンバーのロール変更', async () => {
    await row.getByRole('combobox').selectOption('editor');
    await expect(row.getByRole('combobox')).toHaveValue('editor', { timeout: 30_000 });
  });
});

When('メンバータブで使い捨ての利用者を削除して Server Action の往復を計測する', async ({ page, ctx }) => {
  const row = await openMemberRow(page, ctx);
  page.once('dialog', (dialog) => void dialog.accept());
  await measureAndRecord(page, ctx, 'メンバーの削除', async () => {
    await row.getByRole('button', { name: '削除', exact: true }).click();
    await expect(page.locator('tr', { hasText: member(ctx).email })).toHaveCount(0, { timeout: 30_000 });
  });
});

When('メンバータブで使い捨ての利用者のユーザー情報を同期して Server Action の往復を計測する', async ({ page, ctx }) => {
  const row = await openMemberRow(page, ctx);
  await measureAndRecord(page, ctx, 'メンバーのユーザー情報の同期', async () => {
    await row.getByRole('button', { name: 'ユーザー情報を同期', exact: true }).click();
    await expect(row.getByText('紐づくWordPress環境がありません。')).toBeVisible({ timeout: 30_000 });
  });
});
