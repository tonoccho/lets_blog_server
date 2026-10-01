import { Given, When } from './fixtures';
import { expect, loginViaKeycloak } from '../support';
import { measureServerActionRoundTrip, recordResponseTime } from '../support/responseBudget';
import {
  createSolidPng,
  createThrowawayUser,
  registerUserCleanupByEmail,
  uniqueSuffix,
  waitForHydrated,
  type ThrowawayUser,
} from '../support/responseBudgetFixtures';

/**
 * 利用者管理(`app/users/**`)の Server Action の3秒予算シナリオ(issue #1477、
 * `features/response-budget/server-action-users.feature`)のステップ定義。
 * 計測は共通の `measureServerActionRoundTrip`、判定は共通ステップ(`responseBudget.steps.ts`)。
 * 前提の「応答時間予算の検証用に使い捨ての利用者がいる」は `responseBudgetAdmin.steps.ts` にある。
 */

const USER_KEY = 'responseBudgetUser';

function user(ctx: Record<string, unknown>): ThrowawayUser {
  return ctx[USER_KEY] as ThrowawayUser;
}

// ---- createUserAction ----

When('利用者管理画面で新しい利用者を追加して Server Action の往復を計測する', async ({ page, ctx, request }) => {
  const suffix = uniqueSuffix();
  const email = `e2e-1477-budget-add-${suffix}@example.com`;
  registerUserCleanupByEmail(request, ctx, email);
  await page.goto('/users');
  const form = page.locator('form').filter({ hasText: 'ユーザーを追加' });
  await expect(form).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(form);
  await form.locator('input[name="email"]').fill(email);
  await form.locator('input[name="password"]').fill(`E2e1477Add!${suffix}`);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await form.getByRole('button', { name: '追加', exact: true }).click();
    await expect(form.getByText('追加しました。')).toBeVisible({ timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, '利用者の追加(Server Action)の往復');
});

// ---- deleteUserAction ----

When('利用者管理画面で使い捨ての利用者を削除して Server Action の往復を計測する', async ({ page, ctx }) => {
  await page.goto('/users');
  const row = page.locator('tr', { hasText: user(ctx).email });
  await expect(row).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(row.getByRole('button', { name: '削除' }));
  page.once('dialog', (dialog) => void dialog.accept());
  const timing = await measureServerActionRoundTrip(page, async () => {
    await row.getByRole('button', { name: '削除' }).click();
    await expect(row).toHaveCount(0, { timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, '利用者の削除(Server Action)の往復');
});

// ---- updateUserProfileAction ----

When('使い捨ての利用者の編集画面で名を変更して保存し Server Action の往復を計測する', async ({ page, ctx }) => {
  await page.goto(`/users/${user(ctx).id}/edit`);
  const firstName = page.locator('input[name="firstName"]');
  await expect(firstName).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(firstName);
  await firstName.fill(`予算${uniqueSuffix()}`);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByText('保存しました。')).toBeVisible({ timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'プロフィールの保存(Server Action)の往復');
});

// ---- uploadAvatarAction ----

When(
  '使い捨ての利用者の編集画面でアバター画像を選び切り抜きを確定して Server Action の往復を計測する',
  async ({ page, ctx }) => {
    await page.goto(`/users/${user(ctx).id}/edit`);
    const fileInput = page.getByTestId('avatar-file-input');
    await expect(fileInput).toBeVisible({ timeout: 30_000 });
    await waitForHydrated(fileInput);
    await fileInput.setInputFiles({
      name: 'avatar-source.png',
      mimeType: 'image/png',
      buffer: createSolidPng(600, 600, [30, 120, 200]),
    });
    const confirm = page.getByTestId('avatar-crop-confirm');
    await expect(page.getByTestId('avatar-crop-frame')).toBeVisible({ timeout: 30_000 });
    await expect(confirm).toBeEnabled();
    const timing = await measureServerActionRoundTrip(page, async () => {
      await confirm.click();
      await expect(page.getByTestId('avatar-preview')).toBeVisible({ timeout: 30_000 });
    });
    await expect(page.getByTestId('avatar-upload-error')).toHaveCount(0);
    recordResponseTime(ctx, timing.roundTripMs, 'アバターの保存(Server Action)の往復');
  }
);

// ---- updatePreferencesAction ----

Given('応答時間予算の検証用にログインできる使い捨ての利用者がいる', async ({ request, ctx }) => {
  ctx[USER_KEY] = await createThrowawayUser(request, ctx, { loginable: true });
});

Given('その使い捨ての利用者としてログインしている', async ({ page, ctx }) => {
  // e2e-login-guard:disposable — このシナリオ専用に作った使い捨てアカウント(issue #1477)。共有の E2E 固定アカウントには触れない。
  await loginViaKeycloak(page, user(ctx).email, user(ctx).password);
});

When('自分の編集画面の個人設定でタイムゾーンを変更して保存し Server Action の往復を計測する', async ({ page, ctx }) => {
  await page.goto(`/users/${user(ctx).id}/edit`);
  const tab = page.getByRole('button', { name: '個人設定' });
  await expect(tab).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(tab);
  await tab.click();
  const select = page.getByTestId('timezone-select');
  await expect(select).toBeVisible();
  await select.selectOption('Asia/Tokyo');
  const timing = await measureServerActionRoundTrip(page, async () => {
    await page.getByTestId('preferences-save').click();
    await expect(page.getByTestId('preferences-success')).toBeVisible({ timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, '個人設定の保存(Server Action)の往復');
});
