import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';

/**
 * 非管理者によるユーザー操作の拒否のステップ定義(issue #1160 / AT-4-3、
 * 親issue #930のシナリオ6・7を引き取る子issue)。
 *
 * `userManagement.steps.ts`(issue #1159)・`userDeactivation.steps.ts`(issue #1158)と
 * 同様、兄弟issueと相乗りしない方針のためこのファイル内に閉じて持つ。
 */

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

async function generalUserHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

type UserListItem = { id: number; email: string; role: string; enabled: boolean; keycloakLinked: boolean };

async function fetchUserList(request: APIRequestContext): Promise<UserListItem[]> {
  const headers = await adminHeaders(request);
  const response = await request.get('/api/users', { headers });
  expect(response.ok(), `ユーザー一覧の取得に失敗しました (status=${response.status()})`).toBe(true);
  return (await response.json()) as UserListItem[];
}

// --------------------------------------------------------------- 登録(親シナリオ6)

When('一般ユーザーが新しいメンバーの登録を試みる', async ({ request, ctx }) => {
  const suffix = uniqueSuffix();
  const email = `e2e-1160-authz-${suffix}@example.com`;
  const headers = await generalUserHeaders(request);
  ctx.authzResponse = await request.post('/api/users', {
    headers,
    data: { email, password: `E2e1160Authz!${suffix}`, role: 'user' },
  });
  ctx.authzAttemptedEmail = email;
});

Then('試行したメールアドレスはユーザー一覧に現れていない', async ({ request, ctx }) => {
  const attemptedEmail = ctx.authzAttemptedEmail as string;
  const users = await fetchUserList(request);
  expect(
    users.some((user) => user.email === attemptedEmail),
    `作成できなかったはずの ${attemptedEmail} が一覧に現れています`
  ).toBe(false);
});

// --------------------------------------------------------------- 編集・削除(親シナリオ7)

Given('権限確認用の検証メンバーが登録されている', async ({ request, ctx }) => {
  const suffix = uniqueSuffix();
  const email = `e2e-1160-target-${suffix}@example.com`;
  const headers = await adminHeaders(request);
  const created = await request.post('/api/users', {
    headers,
    data: { email, password: `E2e1160Target!${suffix}`, role: 'user' },
  });
  expect(
    created.ok(),
    `検証用メンバーの登録に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  ctx.authzTargetUserId = ((await created.json()) as { id: number }).id;
});

When('一般ユーザーがそのメンバーのroleをadminへ書き換えようとする', async ({ request, ctx }) => {
  const userId = ctx.authzTargetUserId as number;
  const headers = await generalUserHeaders(request);
  ctx.authzResponse = await request.patch(`/api/users/${userId}`, {
    headers,
    data: { role: 'admin' },
  });
});

When('一般ユーザーがそのメンバーの削除を試みる', async ({ request, ctx }) => {
  const userId = ctx.authzTargetUserId as number;
  const headers = await generalUserHeaders(request);
  ctx.authzResponse = await request.delete(`/api/users/${userId}`, { headers });
});

Then('権限不足として拒否される', async ({ ctx }) => {
  const response = ctx.authzResponse as { status(): number };
  expect(response.status()).toBe(403);
});

Then('そのメンバーのroleはuserのまま変化していない', async ({ request, ctx }) => {
  const userId = ctx.authzTargetUserId as number;
  const headers = await adminHeaders(request);
  const response = await request.get(`/api/users/${userId}`, { headers });
  expect(response.ok(), `プロフィール取得に失敗しました (status=${response.status()})`).toBe(true);
  const profile = (await response.json()) as { role: string };
  expect(profile.role).toBe('user');
});

Then('そのメンバーは一覧から消えずに残っている', async ({ request, ctx }) => {
  const userId = ctx.authzTargetUserId as number;
  const users = await fetchUserList(request);
  expect(
    users.some((user) => user.id === userId),
    `検証用メンバー(id=${userId})が一覧から消えています`
  ).toBe(true);
});

// --------------------------------------------------------------- 後片付け

After({ tags: '@identity' }, async ({ ctx, request }) => {
  const userId = ctx.authzTargetUserId as number | undefined;
  if (userId === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  // 削除はUserService#deleteでローカル・Keycloak双方から消える。既に削除済みでも404を無視する。
  await request.delete(`/api/users/${userId}`, { headers });
});
