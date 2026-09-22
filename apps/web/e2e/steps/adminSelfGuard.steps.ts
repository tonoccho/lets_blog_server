import type { APIRequestContext } from '@playwright/test';
import { kcadm, kcadmLogin, KEYCLOAK_REALM } from '../kcadm';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 自己権限昇格・自己締め出しの防止(issue #1161 / AT-4-4、親issue #930のシナリオ8・9を
 * 引き取る子issue)のステップ定義。
 *
 * `userDeactivation.steps.ts`(issue #1158)と同様、兄弟issueと相乗りしない方針のため
 * このファイル内に閉じて持つ。Keycloakへのパスワード設定手順(kcadm経由、共有モジュール
 * `../kcadm`、issue #1328)を使う——シナリオ9(自己無効化の試行)は「使い捨て管理者アカウント自身」の
 * アクセストークンで行う必要があり、`POST /api/users`が作るユーザーはローカルDBにしか
 * パスワードを持たない(`UserService#create`参照)ため、実際にログインできる状態を別途
 * 整えなければならない。
 *
 * シナリオ8(ROLE_MANAGE保有者の自己特権昇格)の検証用アカウントの作り方は
 * `self-guard.feature`の説明コメントを参照。既定シードでROLE_MANAGE権限を持つロールは
 * ROLE_ADMINのみのため、「ROLE_MANAGE保有者(非admin)」はROLE_ADMINをRBACロールとして
 * 付与することで作る。ロール付与エンドポイントはRBACの`user_roles`しか触らず`users.role`
 * カラム(admin/userの区分)には触れないため、この使い捨てユーザーが実際にadminになることはない。
 * `権限不足として拒否される`は`userAuthorization.steps.ts`(issue #1160)が既に定義している
 * 汎用のThenステップをそのまま再利用する(`ctx.authzResponse`の状態コードを見るだけの
 * ステップのため、このファイルでは同じ`ctx`キーへレスポンスを積む)。
 */

/**
 * 検証用アカウントに実際にログインできるだけのKeycloak資格情報を整える
 * (`scripts/seed-acceptance-env.sh` 2/3手順・`userDeactivation.steps.ts`と同じ内容)。
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
    '-s', 'lastName=SelfGuard',
  ]);
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

type UserListItem = { id: number; role: string; roleNames: string[]; enabled: boolean };

async function findUserInList(request: APIRequestContext, userId: number): Promise<UserListItem> {
  const headers = await adminHeaders(request);
  const response = await request.get('/api/users', { headers });
  expect(response.ok(), `ユーザー一覧の取得に失敗しました (status=${response.status()})`).toBe(true);
  const users = (await response.json()) as UserListItem[];
  const found = users.find((user) => user.id === userId);
  if (!found) {
    throw new Error(`ユーザー一覧に id=${userId} が見つかりません`);
  }
  return found;
}

// --------------------------------------------------------------- シナリオ8: 自己特権昇格

Given('非adminのROLE_MANAGE保有者が使い捨てで用意されている', async ({ request, ctx }) => {
  const suffix = uniqueSuffix();
  const email = `e2e-1161-rolemanage-${suffix}@example.com`;
  const password = `E2e1161Role!${suffix}`;

  const adminHdrs = await adminHeaders(request);
  const created = await request.post('/api/users', {
    headers: adminHdrs,
    data: { email, password, role: 'user' },
  });
  expect(
    created.ok(),
    `検証用アカウントの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const userId = ((await created.json()) as { id: number }).id;

  // 実際にログインできる状態にする(このユーザー自身のトークンで自己昇格を試みるため)。
  provisionLoginableKeycloakCredential(email, password);

  // 既定シードでROLE_MANAGE権限を持つロールはROLE_ADMINのみのため、これを付与して
  // 「ROLE_MANAGE保有者(非admin)」を作る。付与はusers.roleカラムに触れないため、
  // このユーザーが実際にadminになることはない。
  const grant = await request.post(`/api/users/${userId}/roles/ROLE_ADMIN`, { headers: adminHdrs });
  expect(
    grant.ok(),
    `検証前提のROLE_ADMIN付与に失敗しました (status=${grant.status()}): ${await grant.text()}`
  ).toBe(true);

  const before = await findUserInList(request, userId);
  expect(before.role, '検証用アカウントのusers.roleがadminになっている(前提が崩れています)').toBe('user');

  ctx.selfGuardOperatorId = userId;
  ctx.selfGuardOperatorEmail = email;
  ctx.selfGuardOperatorPassword = password;
  ctx.selfGuardOperatorRoleNamesBefore = [...before.roleNames].sort();
});

When('そのROLE_MANAGE保有者が自分自身に特権ロールROLE_ADMINを付与しようとする', async ({ request, ctx }) => {
  const userId = ctx.selfGuardOperatorId as number;
  const email = ctx.selfGuardOperatorEmail as string;
  const password = ctx.selfGuardOperatorPassword as string;
  const token = await fetchAccessToken(request, email, password);
  ctx.authzResponse = await request.post(`/api/users/${userId}/roles/ROLE_ADMIN`, {
    headers: { Authorization: `Bearer ${token}` },
  });
});

Then('そのROLE_MANAGE保有者の権限は操作前と変化していない', async ({ request, ctx }) => {
  const userId = ctx.selfGuardOperatorId as number;
  const before = ctx.selfGuardOperatorRoleNamesBefore as string[];
  const after = await findUserInList(request, userId);
  expect(after.role, '拒否されたはずの操作でusers.roleが変化しています').toBe('user');
  expect([...after.roleNames].sort(), 'ロール付与が拒否されずに反映されています').toEqual(before);
});

// --------------------------------------------------------------- シナリオ9: 自己無効化

Given('使い捨ての管理者アカウントが用意されており、自分自身でログインできる', async ({ request, ctx }) => {
  const suffix = uniqueSuffix();
  const email = `e2e-1161-selfadmin-${suffix}@example.com`;
  const password = `E2e1161Admin!${suffix}`;

  const adminHdrs = await adminHeaders(request);
  const created = await request.post('/api/users', {
    headers: adminHdrs,
    data: { email, password, role: 'admin' },
  });
  expect(
    created.ok(),
    `検証用管理者アカウントの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const userId = ((await created.json()) as { id: number }).id;

  provisionLoginableKeycloakCredential(email, password);

  const token = await fetchAccessToken(request, email, password);
  expect(token, '検証用管理者アカウントのログインに失敗しました(前提が崩れています)').toBeTruthy();

  ctx.selfGuardAdminId = userId;
  ctx.selfGuardAdminEmail = email;
  ctx.selfGuardAdminPassword = password;
});

When('その使い捨て管理者が自分自身の無効化を試みる', async ({ request, ctx }) => {
  const userId = ctx.selfGuardAdminId as number;
  const email = ctx.selfGuardAdminEmail as string;
  const password = ctx.selfGuardAdminPassword as string;
  const token = await fetchAccessToken(request, email, password);
  ctx.authzResponse = await request.post(`/api/users/${userId}/deactivate`, {
    headers: { Authorization: `Bearer ${token}` },
  });
});

Then('その使い捨て管理者のアカウントは有効なまま変化していない', async ({ request, ctx }) => {
  const userId = ctx.selfGuardAdminId as number;
  const after = await findUserInList(request, userId);
  expect(after.enabled, '拒否されたはずの自己無効化でenabledがfalseになっています').toBe(true);
});

// --------------------------------------------------------------- 後片付け

After({ tags: '@identity' }, async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const operatorId = ctx.selfGuardOperatorId as number | undefined;
  if (operatorId !== undefined) {
    await request.delete(`/api/users/${operatorId}`, { headers });
  }
  const adminId = ctx.selfGuardAdminId as number | undefined;
  if (adminId !== undefined) {
    await request.delete(`/api/users/${adminId}`, { headers });
  }
});
