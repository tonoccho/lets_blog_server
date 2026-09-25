import type { APIRequestContext } from '@playwright/test';
import { kcadm, kcadmLogin, KEYCLOAK_REALM } from '../kcadm';
import { Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  expect,
  fetchAccessToken,
  loginViaKeycloak,
} from '../support';
import { clickUntilVisible } from '../support/retryClick';

/**
 * issue #1259: 個人設定のタイムゾーンを任意の上書きにする、のステップ定義。
 *
 * `userManagement.steps.ts` / `avatarUpload.steps.ts` と同様、ステップ定義ファイルは
 * 兄弟issueと相乗りしない方針のため、必要なヘルパーはこのファイル内に閉じて持つ
 * (Keycloak側の資格情報整え(kcadm経由)は共有モジュール`../kcadm`、issue #1328)。
 */

function provisionLoginableKeycloakCredential(email: string, password: string): void {
  kcadmLogin();
  const usersJson = kcadm(['get', 'users', '-r', KEYCLOAK_REALM, '-q', `email=${email}`, '--fields', 'id']);
  const users = JSON.parse(usersJson) as { id: string }[];
  if (users.length === 0) {
    throw new Error(`Keycloakに ${email} が見つかりません`);
  }
  const keycloakUserId = users[0].id;

  kcadm(['set-password', '-r', KEYCLOAK_REALM, '--userid', keycloakUserId, '--new-password', password]);
  kcadm([
    'update', `users/${keycloakUserId}`, '-r', KEYCLOAK_REALM,
    '-s', 'requiredActions=[]',
    '-s', 'emailVerified=true',
    '-s', 'firstName=E2E',
    '-s', 'lastName=Timezone',
  ]);
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

interface TimezoneFixture {
  userId: number;
  email: string;
  password: string;
}

async function createFixture(request: APIRequestContext, ctx: Record<string, unknown>): Promise<TimezoneFixture> {
  const suffix = uniqueSuffix();
  const email = `e2e-1259-tz-${suffix}@example.com`;
  const password = `E2e1259Tz!${suffix}`;

  const headers = await adminHeaders(request);
  const created = await request.post('/api/users', {
    headers,
    data: { email, password, role: 'user' },
  });
  expect(
    created.ok(),
    `検証用メンバーの登録に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const userId = ((await created.json()) as { id: number }).id;

  ctx.timezoneOverrideUserId = userId;
  ctx.timezoneOverrideEmail = email;
  ctx.timezoneOverridePassword = password;

  return { userId, email, password };
}

function fixture(ctx: Record<string, unknown>): TimezoneFixture {
  return {
    userId: ctx.timezoneOverrideUserId as number,
    email: ctx.timezoneOverrideEmail as string,
    password: ctx.timezoneOverridePassword as string,
  };
}

async function fetchOwnProfile(request: APIRequestContext, token: string): Promise<{ timezone: string | null }> {
  const response = await request.get('/api/identity/me', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.ok(), `本人情報の取得に失敗しました (status=${response.status()})`).toBe(true);
  return (await response.json()) as { timezone: string | null };
}

// --------------------------------------------------------------- AC1: 新規作成時は未設定

When('管理者がタイムゾーン検証用の新しいメンバーを登録する', async ({ request, ctx }) => {
  await createFixture(request, ctx);
});

Then('そのメンバーの本人情報のタイムゾーンが未設定である', async ({ request, ctx }) => {
  const { userId } = fixture(ctx);
  const headers = await adminHeaders(request);
  const response = await request.get(`/api/users/${userId}`, { headers });
  expect(response.ok(), `プロフィール取得に失敗しました (status=${response.status()})`).toBe(true);
  const profile = (await response.json()) as { timezone: string | null };
  expect(profile.timezone).toBeNull();
});

// --------------------------------------------------------------- AC3: 「ブラウザに従う」の選択・保持

Given('タイムゾーン検証用のログイン可能なメンバーが登録されている', async ({ request, ctx }) => {
  const { email, password } = await createFixture(request, ctx);
  provisionLoginableKeycloakCredential(email, password);
});

When('そのメンバーで個人設定タブを開く', async ({ page, ctx }) => {
  const { userId, email, password } = fixture(ctx);
  await loginViaKeycloak(page, email, password);
  await page.goto(`/users/${userId}/edit`);
  // issue #1381: `goto`直後はReactのハイドレーションが完了しておらず、サーバ描画済みの
  // タブボタンをクリックしても空振りすることがある(#1381本文参照)。「個人設定」タブは
  // べき等な切り替えのみで開閉トグルではないため(apps/web/src/components/Tabs.tsxの
  // Tabsコンポーネントは`setActiveTabId`を呼ぶだけで、同じタブへの再クリックは
  // 何も閉じない)、`clickUntilVisible`で再試行しても安全。
  await clickUntilVisible(
    page.getByRole('button', { name: '個人設定' }),
    page.locator('[data-testid="timezone-select"]')
  );
});

When(/^タイムゾーンで「ブラウザに従う\(未設定\)」を選んで保存する$/, async ({ page }) => {
  await page.locator('[data-testid="timezone-select"]').selectOption('');
  await page.locator('[data-testid="preferences-save"]').click();
  await expect(page.locator('[data-testid="preferences-success"]')).toBeVisible();
});

Then(/^画面を再読み込みしても「ブラウザに従う\(未設定\)」が選択されている$/, async ({ page }) => {
  await page.reload();
  // issue #1381: `reload`直後も`goto`直後と同じハイドレーション未完了の空振りが起こりうる。
  await clickUntilVisible(
    page.getByRole('button', { name: '個人設定' }),
    page.locator('[data-testid="timezone-select"]')
  );
  await expect(page.locator('[data-testid="timezone-select"]')).toHaveValue('');
});

// --------------------------------------------------------------- AC4: 保存した値がそのまま本人情報へ反映される

When('そのメンバーが自分のタイムゾーンをPacific-Aucklandに保存する', async ({ request, ctx }) => {
  const { email, password } = fixture(ctx);
  const token = await fetchAccessToken(request, email, password);
  const response = await request.patch('/api/identity/me/preferences', {
    headers: { Authorization: `Bearer ${token}` },
    data: { timezone: 'Pacific/Auckland', locale: 'ja' },
  });
  expect(
    response.ok(),
    `本人設定の保存に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.timezoneOverrideToken = token;
});

Then('そのメンバーの本人情報のタイムゾーンがPacific-Aucklandである', async ({ request, ctx }) => {
  const token = ctx.timezoneOverrideToken as string;
  const profile = await fetchOwnProfile(request, token);
  expect(profile.timezone).toBe('Pacific/Auckland');
});
