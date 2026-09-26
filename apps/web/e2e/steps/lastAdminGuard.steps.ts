import type { APIRequestContext } from '@playwright/test';
import { kcadm, kcadmLogin, KEYCLOAK_REALM } from '../kcadm';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 最後の管理者の保護(issue #1162、親issue #930のシナリオ10)のステップ定義。
 *
 * 「有効な管理者が1人だけ」「同時に2つの削除」は共有の受け入れ環境ではAPI越しに決定的に作れない
 * ため、ここでは「管理者が2人以上なら互いに操作できる」回帰だけを扱う(理由は
 * `last-admin-guard.feature`の説明コメントと`LastAdminGuardIntegrationTest`を参照)。
 * 兄弟issueのステップ定義とは相乗りせず、このファイル内に閉じて持つ(`adminSelfGuard.steps.ts`と同方針)。
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
    '-s', 'lastName=LastAdminGuard',
  ]);
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

async function createThrowawayAdmin(request: APIRequestContext, email: string, password: string): Promise<number> {
  const created = await request.post('/api/users', {
    headers: await adminHeaders(request),
    data: { email, password, role: 'admin' },
  });
  expect(
    created.ok(),
    `検証用管理者アカウントの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  return ((await created.json()) as { id: number }).id;
}

type UserListItem = { id: number; enabled: boolean };

async function findUserInList(request: APIRequestContext, userId: number): Promise<UserListItem | undefined> {
  const response = await request.get('/api/users', { headers: await adminHeaders(request) });
  expect(response.ok(), `ユーザー一覧の取得に失敗しました (status=${response.status()})`).toBe(true);
  return ((await response.json()) as UserListItem[]).find((user) => user.id === userId);
}

Given('使い捨ての管理者が2人用意されており、片方は自分自身でログインできる', async ({ request, ctx }) => {
  const suffix = uniqueSuffix();
  const actorEmail = `e2e-1162-actor-${suffix}@example.com`;
  const actorPassword = `E2e1162Actor!${suffix}`;

  ctx.lastAdminActorId = await createThrowawayAdmin(request, actorEmail, actorPassword);
  ctx.lastAdminTargetId = await createThrowawayAdmin(
    request,
    `e2e-1162-target-${suffix}@example.com`,
    `E2e1162Target!${suffix}`
  );

  provisionLoginableKeycloakCredential(actorEmail, actorPassword);
  ctx.lastAdminActorEmail = actorEmail;
  ctx.lastAdminActorPassword = actorPassword;
});

async function actorHeaders(request: APIRequestContext, ctx: Record<string, unknown>): Promise<Record<string, string>> {
  const token = await fetchAccessToken(
    request,
    ctx.lastAdminActorEmail as string,
    ctx.lastAdminActorPassword as string
  );
  return { Authorization: `Bearer ${token}` };
}

When('ログインできる方の管理者がもう一方の管理者を削除する', async ({ request, ctx }) => {
  ctx.lastAdminResponse = await request.delete(`/api/users/${ctx.lastAdminTargetId}`, {
    headers: await actorHeaders(request, ctx),
  });
});

When('ログインできる方の管理者がもう一方の管理者を無効化する', async ({ request, ctx }) => {
  ctx.lastAdminResponse = await request.post(`/api/users/${ctx.lastAdminTargetId}/deactivate`, {
    headers: await actorHeaders(request, ctx),
  });
});

Then('削除は成功する', async ({ ctx }) => {
  const response = ctx.lastAdminResponse as { status(): number };
  expect(response.status(), '管理者が2人いるのに削除が拒否されました').toBe(204);
});

Then('削除された管理者はユーザー一覧に存在しない', async ({ request, ctx }) => {
  expect(await findUserInList(request, ctx.lastAdminTargetId as number)).toBeUndefined();
});

Then('無効化は成功する', async ({ ctx }) => {
  const response = ctx.lastAdminResponse as { status(): number };
  expect(response.status(), '管理者が2人いるのに無効化が拒否されました').toBe(200);
});

Then('無効化された管理者はユーザー一覧で無効になっている', async ({ request, ctx }) => {
  const found = await findUserInList(request, ctx.lastAdminTargetId as number);
  expect(found, '無効化した管理者がユーザー一覧に見つかりません').toBeDefined();
  expect(found!.enabled).toBe(false);
});

After({ tags: '@identity' }, async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  for (const id of [ctx.lastAdminActorId, ctx.lastAdminTargetId] as (number | undefined)[]) {
    if (id !== undefined) {
      await request.delete(`/api/users/${id}`, { headers });
    }
  }
});
