import { Given, When } from './fixtures';
import { expect } from '../support';
import { measureServerActionRoundTrip, recordResponseTime } from '../support/responseBudget';
import {
  adminHeaders,
  createThrowawayUser,
  registerCleanup,
  uniqueSuffix,
  waitForHydrated,
  type ThrowawayUser,
} from '../support/responseBudgetFixtures';

/**
 * 管理画面(`app/admin/**`)の Server Action の3秒予算シナリオ(issue #1477、
 * `features/response-budget/server-action-admin.feature`)のステップ定義。
 *
 * 計測は共通の `measureServerActionRoundTrip`(§10.3)を呼ぶだけで、画面ごとの独自の計測は持たない。
 * 判定は共通ステップ「Server Action の往復は「…」ミリ秒以内に返る」(`responseBudget.steps.ts`)。
 * ここに書くのは、対象の操作を画面で起こすところまで。
 */

const USER_KEY = 'responseBudgetUser';

function user(ctx: Record<string, unknown>): ThrowawayUser {
  return ctx[USER_KEY] as ThrowawayUser;
}

// ---- ロール管理(assignRoleAction / removeRoleAction) ----

Given('応答時間予算の検証用に使い捨ての利用者がいる', async ({ request, ctx }) => {
  ctx[USER_KEY] = await createThrowawayUser(request, ctx);
});

Given(/^応答時間予算の検証用に「([^」]+)」を持つ使い捨ての利用者がいる$/, async ({ request, ctx }, roleName: string) => {
  const created = await createThrowawayUser(request, ctx);
  ctx[USER_KEY] = created;
  const response = await request.post(`/api/users/${created.id}/roles/${roleName}`, {
    headers: await adminHeaders(request),
  });
  expect(response.ok(), `ロールの付与に失敗しました (status=${response.status()})`).toBe(true);
});

async function openRoleRow(page: import('@playwright/test').Page, email: string) {
  await page.goto('/admin/roles');
  const row = page.locator('tr', { hasText: email });
  await expect(row).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(row.getByRole('combobox'));
  return row;
}

When(
  /^ロール管理画面で使い捨ての利用者に「([^」]+)」を付与して Server Action の往復を計測する$/,
  async ({ page, ctx }, roleName: string) => {
    const row = await openRoleRow(page, user(ctx).email);
    await row.getByRole('combobox').selectOption(roleName);
    const timing = await measureServerActionRoundTrip(page, async () => {
      await row.getByRole('button', { name: '割り当て' }).click();
      await expect(row.getByLabel(`${roleName}を解除`)).toBeVisible({ timeout: 30_000 });
    });
    recordResponseTime(ctx, timing.roundTripMs, 'ロールの付与(Server Action)の往復');
  }
);

When(
  /^ロール管理画面で使い捨ての利用者から「([^」]+)」を解除して Server Action の往復を計測する$/,
  async ({ page, ctx }, roleName: string) => {
    const row = await openRoleRow(page, user(ctx).email);
    await expect(row.getByLabel(`${roleName}を解除`)).toBeVisible();
    const timing = await measureServerActionRoundTrip(page, async () => {
      await row.getByLabel(`${roleName}を解除`).click();
      await expect(row.getByLabel(`${roleName}を解除`)).toHaveCount(0, { timeout: 30_000 });
    });
    recordResponseTime(ctx, timing.roundTripMs, 'ロールの解除(Server Action)の往復');
  }
);

// ---- SSH鍵ペア(createSshKeyPairAction / deleteSshKeyPairAction) ----

When('SSH鍵管理画面で新しいSSH鍵ペアを生成して Server Action の往復を計測する', async ({ page, ctx, request }) => {
  const name = `e2e-1477-create-${uniqueSuffix()}`;
  registerSshKeyCleanupByName(request, ctx, name);
  await page.goto('/admin/ssh-keys');
  await waitForHydrated(page.locator('form').first());
  await page.locator('input[type="text"]').first().fill(name);
  const timing = await measureServerActionRoundTrip(page, async () => {
    // 送信ボタンはハイドレーション完了までは押せない(#1413)ので、1回のクリックで済む(非べき等な操作)。
    await page.getByRole('button', { name: 'SSH鍵ペアを生成' }).click();
    await expect(page.getByText(`「${name}」を生成しました。`)).toBeVisible({ timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'SSH鍵ペアの生成(Server Action)の往復');
});

function registerSshKeyCleanupByName(
  request: import('@playwright/test').APIRequestContext,
  ctx: Record<string, unknown>,
  name: string
): void {
  registerCleanup(ctx, async () => {
    const headers = await adminHeaders(request);
    const response = await request.get('/api/ssh-key-pairs', { headers });
    if (!response.ok()) return;
    for (const keyPair of (await response.json()) as { id: number; name: string }[]) {
      if (keyPair.name === name) await request.delete(`/api/ssh-key-pairs/${keyPair.id}`, { headers });
    }
  });
}

Given('応答時間予算の検証用のSSH鍵ペアがある', async ({ request, ctx }) => {
  const name = `e2e-1477-delete-${uniqueSuffix()}`;
  registerSshKeyCleanupByName(request, ctx, name);
  const response = await request.post('/api/ssh-key-pairs', {
    headers: await adminHeaders(request),
    data: { name },
  });
  expect(response.ok(), `SSH鍵ペアの作成に失敗しました (status=${response.status()})`).toBe(true);
  ctx.responseBudgetSshKeyName = name;
});

When('SSH鍵管理画面でそのSSH鍵ペアを削除して Server Action の往復を計測する', async ({ page, ctx }) => {
  await page.goto('/admin/ssh-keys');
  const row = page.locator('tr', { hasText: ctx.responseBudgetSshKeyName as string });
  await expect(row).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(row.getByRole('button', { name: '削除' }));
  page.once('dialog', (dialog) => void dialog.accept());
  const timing = await measureServerActionRoundTrip(page, async () => {
    await row.getByRole('button', { name: '削除' }).click();
    await expect(row).toHaveCount(0, { timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'SSH鍵ペアの削除(Server Action)の往復');
});

// ---- システム設定(updateAppSettingsAction) ----

When('システム設定画面で表示されたままの値を保存して Server Action の往復を計測する', async ({ page, ctx }) => {
  await page.goto('/admin/system-settings');
  await expect(page.getByRole('button', { name: 'まとめて保存' })).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(page.locator('form').first());
  const timing = await measureServerActionRoundTrip(page, async () => {
    await page.getByRole('button', { name: 'まとめて保存' }).click();
    await expect(page.getByText('保存しました。')).toBeVisible({ timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'システム設定の保存(Server Action)の往復');
});

// ---- バックアップ(restoreBackupAction) ----

When(
  'バックアップ画面でバックアップとして読めないファイルのリストアを実行して Server Action の往復を計測する',
  async ({ page, ctx }) => {
    await page.goto('/admin/backup');
    await expect(page.getByRole('button', { name: 'リストアを実行' })).toBeVisible({ timeout: 30_000 });
    await waitForHydrated(page.locator('form').first());
    await page.locator('input[type="file"][name="file"]').setInputFiles({
      name: 'not-a-backup.zip',
      mimeType: 'application/zip',
      buffer: Buffer.from('これはバックアップではありません(応答時間予算の検証用)'),
    });
    page.once('dialog', (dialog) => void dialog.accept());
    const timing = await measureServerActionRoundTrip(page, async () => {
      await page.getByRole('button', { name: 'リストアを実行' }).click();
      // 拒否の理由(赤字)が出る = サーバが中身を検証して断った。成功の表示は出ない。
      await expect(page.locator('form p.text-red-600')).toBeVisible({ timeout: 30_000 });
    });
    await expect(page.getByText('リストアが完了しました。')).toHaveCount(0);
    recordResponseTime(ctx, timing.roundTripMs, 'バックアップのリストア要求(Server Action)の往復');
  }
);
