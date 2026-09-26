import type { APIRequestContext } from '@playwright/test';
import { kcadm, kcadmLogin, KEYCLOAK_REALM } from '../kcadm';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * ユーザー管理(登録・編集・削除・本人設定)のステップ定義(issue #1159 / AT-4-1、
 * 親issue #930のシナリオ1・2・5・17を引き取る子issue)。
 *
 * `userDeactivation.steps.ts`(issue #1158)と同様、Keycloak側の状態を直接確認するために
 * `docker exec` で `kcadm.sh` を呼ぶ(共有モジュール`../kcadm`、issue #1328)。
 * ステップ定義ファイルは兄弟issueと相乗りしない方針(issue本文参照)のため、必要な
 * ヘルパーはこのファイル内に閉じて持つ。
 */

/** 指定したメールのユーザーがKeycloakに存在するかどうか。 */
function existsInKeycloak(email: string): boolean {
  kcadmLogin();
  const usersJson = kcadm(['get', 'users', '-r', KEYCLOAK_REALM, '-q', `email=${email}`, '--fields', 'id']);
  const users = JSON.parse(usersJson) as { id: string }[];
  return users.length > 0;
}

/**
 * 検証用アカウントに実際にログインできるだけのKeycloak資格情報を整える
 * (`scripts/seed-acceptance-env.sh` 2/3手順と同じ内容。`userDeactivation.steps.ts`と同型)。
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

  // VERIFY_PROFILEが有効なレルムのため、firstName/lastNameが空だとパスワードグラントが
  // "Account is not fully set up" で失敗する(scripts/seed-acceptance-env.sh 参照)。
  kcadm([
    'update', `users/${keycloakUserId}`, '-r', KEYCLOAK_REALM,
    '-s', 'requiredActions=[]',
    '-s', 'emailVerified=true',
    '-s', 'firstName=E2E',
    '-s', 'lastName=Management',
  ]);
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

type UserListItem = { id: number; email: string; role: string; enabled: boolean; keycloakLinked: boolean };

async function fetchUserList(request: APIRequestContext): Promise<UserListItem[]> {
  const headers = await adminHeaders(request);
  const response = await request.get('/api/users', { headers });
  expect(response.ok(), `ユーザー一覧の取得に失敗しました (status=${response.status()})`).toBe(true);
  return (await response.json()) as UserListItem[];
}

async function createManagementFixture(request: APIRequestContext, ctx: Record<string, unknown>): Promise<void> {
  const suffix = uniqueSuffix();
  const email = `e2e-1159-management-${suffix}@example.com`;
  const password = `E2e1159Mgmt!${suffix}`;

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

  ctx.userManagementEmail = email;
  ctx.userManagementPassword = password;
  ctx.userManagementUserId = userId;
}

// --------------------------------------------------------------- 登録(親シナリオ1)

When('管理者が新しいメンバーを登録する', async ({ request, ctx }) => {
  await createManagementFixture(request, ctx);
});

Then('そのメンバーがユーザー一覧に現れる', async ({ request, ctx }) => {
  const email = ctx.userManagementEmail as string;
  const users = await fetchUserList(request);
  expect(users.some((user) => user.email === email), `一覧に ${email} が見つかりません`).toBe(true);
});

Then('そのメンバーがKeycloakにも登録されている', async ({ ctx }) => {
  const email = ctx.userManagementEmail as string;
  expect(existsInKeycloak(email), `Keycloakに ${email} が見つかりません`).toBe(true);
});

// --------------------------------------------------------------- 編集(親シナリオ2、表示名のみ)

Given('検証用メンバーが登録されている', async ({ request, ctx }) => {
  await createManagementFixture(request, ctx);
});

When('管理者がそのメンバーの表示名を編集する', async ({ request, ctx }) => {
  const userId = ctx.userManagementUserId as number;
  const newDisplayName = `E2E管理更新済み-${uniqueSuffix()}`;
  const headers = await adminHeaders(request);
  const response = await request.put(`/api/users/${userId}`, {
    headers,
    data: { displayName: newDisplayName },
  });
  expect(
    response.ok(),
    `プロフィール更新に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.userManagementDisplayName = newDisplayName;
});

Then('一覧のそのメンバーの表示名が更新されている', async ({ request, ctx }) => {
  const userId = ctx.userManagementUserId as number;
  const expectedDisplayName = ctx.userManagementDisplayName as string;
  const headers = await adminHeaders(request);
  const response = await request.get(`/api/users/${userId}`, { headers });
  expect(response.ok(), `プロフィール取得に失敗しました (status=${response.status()})`).toBe(true);
  const profile = (await response.json()) as { displayName: string | null };
  expect(profile.displayName).toBe(expectedDisplayName);
});

// --------------------------------------------------------------- 編集(親シナリオ2、メールアドレス。issue #1192)

/** 指定メールのKeycloakユーザーの username を返す(存在しなければ null)。 */
function keycloakUsernameByEmail(email: string): string | null {
  kcadmLogin();
  const usersJson = kcadm(['get', 'users', '-r', KEYCLOAK_REALM, '-q', `email=${email}`, '--fields', 'username']);
  const users = JSON.parse(usersJson) as { username: string }[];
  return users.length > 0 ? users[0].username : null;
}

When('管理者がそのメンバーのメールアドレスを編集する', async ({ request, ctx }) => {
  const userId = ctx.userManagementUserId as number;
  const newEmail = `e2e-1192-renamed-${uniqueSuffix()}@example.com`;
  const headers = await adminHeaders(request);
  const response = await request.put(`/api/users/${userId}`, { headers, data: { email: newEmail } });
  expect(
    response.ok(),
    `メールアドレス更新に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.userManagementNewEmail = newEmail;
});

Then('一覧のそのメンバーのメールアドレスが更新されている', async ({ request, ctx }) => {
  const userId = ctx.userManagementUserId as number;
  const users = await fetchUserList(request);
  expect(users.find((user) => user.id === userId)?.email).toBe(ctx.userManagementNewEmail);
});

Then('Keycloakのそのメンバーのメールアドレスとユーザー名も更新されている', async ({ ctx }) => {
  const newEmail = ctx.userManagementNewEmail as string;
  expect(keycloakUsernameByEmail(newEmail), `Keycloakに ${newEmail} が見つからない、またはusernameが追随していません`)
    .toBe(newEmail);
});

When('管理者がそのメンバーのメールアドレスを既存ユーザーのものへ編集しようとする', async ({ request, ctx }) => {
  const userId = ctx.userManagementUserId as number;
  const headers = await adminHeaders(request);
  const response = await request.put(`/api/users/${userId}`, { headers, data: { email: E2E_ADMIN_EMAIL } });
  ctx.userManagementDuplicateStatus = response.status();
});

Then('メールアドレスの変更が重複として拒否される', async ({ ctx }) => {
  expect(ctx.userManagementDuplicateStatus).toBe(409);
});

Then('一覧のそのメンバーのメールアドレスは変更されていない', async ({ request, ctx }) => {
  const userId = ctx.userManagementUserId as number;
  const users = await fetchUserList(request);
  expect(users.find((user) => user.id === userId)?.email).toBe(ctx.userManagementEmail);
});

// --------------------------------------------------------------- 削除(親シナリオ5)

When('管理者がそのメンバーを削除する', async ({ request, ctx }) => {
  const userId = ctx.userManagementUserId as number;
  const headers = await adminHeaders(request);
  const response = await request.delete(`/api/users/${userId}`, { headers });
  expect(response.ok(), `削除に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  // 後片付けの二重削除(404)を避けるため、削除済みであることを記録しておく。
  ctx.userManagementDeleted = true;
});

Then('そのメンバーが一覧から消えている', async ({ request, ctx }) => {
  const email = ctx.userManagementEmail as string;
  const users = await fetchUserList(request);
  expect(users.some((user) => user.email === email), `削除したはずの ${email} が一覧に残っています`).toBe(false);
});

Then('そのメンバーがKeycloakからも消えている', async ({ ctx }) => {
  const email = ctx.userManagementEmail as string;
  expect(existsInKeycloak(email), `削除したはずの ${email} がKeycloakに残っています`).toBe(false);
});

// --------------------------------------------------------------- 本人設定(親シナリオ17、#784の退行検知)

Given('ログイン可能な検証用メンバーが登録されている', async ({ request, ctx }) => {
  await createManagementFixture(request, ctx);
  const email = ctx.userManagementEmail as string;
  const password = ctx.userManagementPassword as string;
  // POST /api/usersのpasswordはローカルDBにしか反映されないため、実際にログインできる
  // ようにKeycloak側の資格情報を別途整える(userDeactivation.steps.tsと同じ理由)。
  provisionLoginableKeycloakCredential(email, password);
});

When('そのメンバーが自分のタイムゾーンとロケールを保存する', async ({ request, ctx }) => {
  const email = ctx.userManagementEmail as string;
  const password = ctx.userManagementPassword as string;
  const token = await fetchAccessToken(request, email, password);

  const timezone = 'Asia/Tokyo';
  const locale = 'ja';
  const response = await request.patch('/api/identity/me/preferences', {
    headers: { Authorization: `Bearer ${token}` },
    data: { timezone, locale },
  });
  expect(
    response.ok(),
    `本人設定の保存に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);

  ctx.userManagementTimezone = timezone;
  ctx.userManagementLocale = locale;
});

When('そのメンバーが再ログインする', async ({ request, ctx }) => {
  const email = ctx.userManagementEmail as string;
  const password = ctx.userManagementPassword as string;
  // 新しいアクセストークンを取り直すことを「再ログイン」として扱う。セッションを跨いで
  // 保存内容が引き継がれるかを見るのが目的であり、UIのログイン画面を経由する必要はない。
  ctx.userManagementReloginToken = await fetchAccessToken(request, email, password);
});

Then(
  'そのメンバーの個人設定に保存したタイムゾーンとロケールが反映されている',
  async ({ request, ctx }) => {
    const token = ctx.userManagementReloginToken as string;
    const response = await request.get('/api/identity/me', {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(response.ok(), `本人情報の取得に失敗しました (status=${response.status()})`).toBe(true);
    const profile = (await response.json()) as { timezone: string | null; locale: string | null };
    expect(profile.timezone).toBe(ctx.userManagementTimezone);
    expect(profile.locale).toBe(ctx.userManagementLocale);
  }
);

// --------------------------------------------------------------- 後片付け

After({ tags: '@identity' }, async ({ ctx, request }) => {
  if (ctx.userManagementDeleted === true) {
    return;
  }
  const userId = ctx.userManagementUserId as number | undefined;
  if (userId === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  // 削除はUserService#deleteでローカル・Keycloak双方から消える。既に削除済みでも404を無視する。
  await request.delete(`/api/users/${userId}`, { headers });
});
