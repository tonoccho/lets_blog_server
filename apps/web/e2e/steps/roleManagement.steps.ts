import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken, loginViaKeycloak } from '../support';

/**
 * ロールと権限の付与・剥奪の反映のステップ定義(issue #1163 / AT-4-6、
 * 親issue #930のシナリオ11・12・13を引き取る子issue)。
 *
 * `userManagement.steps.ts`(issue #1159)などと同様、ステップ定義ファイルは兄弟issueと
 * 相乗りしない方針(issue本文参照)のため、必要なヘルパーはこのファイル内に閉じて持つ。
 */

const KEYCLOAK_CONTAINER = 'lbs-keycloak';
const KEYCLOAK_REALM = 'letsblog';
const KCADM_BIN = '/opt/keycloak/bin/kcadm.sh';

/** リポジトリルート(apps/web/e2e/steps から4階層上)。`.env`からKeycloakのmaster管理者資格情報を読む。 */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

function readEnvValue(key: string): string {
  const envPath = path.join(REPO_ROOT, '.env');
  const content = fs.readFileSync(envPath, 'utf-8');
  const match = content.match(new RegExp(`^${key}=(.*)$`, 'm'));
  if (!match) {
    throw new Error(`.env に ${key} が見つかりません`);
  }
  return match[1].trim();
}

function kcadm(args: string[]): string {
  return execFileSync(
    'docker',
    ['exec', KEYCLOAK_CONTAINER, KCADM_BIN, ...args],
    { encoding: 'utf-8', timeout: 30_000 }
  );
}

function kcadmLogin(): void {
  const username = readEnvValue('KEYCLOAK_ADMIN_USERNAME');
  const password = readEnvValue('KEYCLOAK_ADMIN_PASSWORD');
  kcadm([
    'config', 'credentials',
    '--server', 'http://localhost:8080/auth',
    '--realm', 'master',
    '--user', username,
    '--password', password,
  ]);
}

/**
 * 検証用アカウントに実際にログインできるだけのKeycloak資格情報を整える
 * (`scripts/seed-acceptance-env.sh` 2/3手順と同じ内容。`userManagement.steps.ts`と同型)。
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
    '-s', 'lastName=Role',
  ]);
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

type RoleInfo = {
  id: number;
  roleName: string;
  displayName: string;
  description: string | null;
  permissions: string[];
};

async function fetchRoles(request: APIRequestContext): Promise<RoleInfo[]> {
  const headers = await adminHeaders(request);
  const response = await request.get('/api/roles', { headers });
  expect(response.ok(), `ロール一覧の取得に失敗しました (status=${response.status()})`).toBe(true);
  return (await response.json()) as RoleInfo[];
}

async function fetchPermissions(request: APIRequestContext, userId: number): Promise<string[]> {
  const headers = await adminHeaders(request);
  const response = await request.get(`/api/identity/users/${userId}/permissions`, { headers });
  expect(response.ok(), `権限の取得に失敗しました (status=${response.status()})`).toBe(true);
  return ((await response.json()) as { permissions: string[] }).permissions;
}

async function createRoleVerificationFixture(
  request: APIRequestContext,
  ctx: Record<string, unknown>
): Promise<void> {
  const suffix = uniqueSuffix();
  const email = `e2e-1163-role-${suffix}@example.com`;
  const password = `E2e1163Role!${suffix}`;

  const headers = await adminHeaders(request);
  const created = await request.post('/api/users', {
    headers,
    data: { email, password, role: 'user' },
  });
  expect(
    created.ok(),
    `ロール検証用メンバーの登録に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const userId = ((await created.json()) as { id: number }).id;

  ctx.roleUserId = userId;
  ctx.roleEmail = email;
  ctx.rolePassword = password;
}

// --------------------------------------------------------------- ロール一覧(親シナリオ11)

When('ロール管理画面を開く', async ({ page }) => {
  await page.goto('/admin/roles');
});

Then('画面に表示されている各ロールの権限一覧がロール一覧APIの内容と一致する', async ({ page, request }) => {
  const roles = await fetchRoles(request);
  expect(roles.length, 'GET /api/roles が1件もロールを返していません').toBeGreaterThan(0);

  const main = page.locator('main');
  for (const role of roles) {
    // apps/web/src/app/admin/roles/page.tsx のマークアップ: ロールごとのカードは
    // <p className="text-xs ...">{role.roleName}</p> の直接の親div(rounded-lg border...)。
    // roleNameは一意なので、そのpの1つ上の階層をカード全体として扱う。
    const roleNameLabel = main.locator('p.text-xs', { hasText: role.roleName }).first();
    await expect(roleNameLabel, `ロール ${role.roleName} が画面に見つかりません`).toBeVisible();
    const card = roleNameLabel.locator('..');

    for (const permission of role.permissions) {
      await expect(
        card.getByText(permission, { exact: true }),
        `ロール ${role.roleName} の権限 ${permission} が画面に表示されていません`
      ).toBeVisible();
    }
  }
});

// --------------------------------------------------------------- ロール付与/剥奪(親シナリオ12)

Given('ロール検証用のメンバーが登録されている', async ({ request, ctx }) => {
  await createRoleVerificationFixture(request, ctx);
});

When('管理者がそのメンバーにROLE_EDITORロールを付与する', async ({ request, ctx }) => {
  const userId = ctx.roleUserId as number;
  const headers = await adminHeaders(request);
  const response = await request.post(`/api/users/${userId}/roles/ROLE_EDITOR`, { headers });
  expect(
    response.ok(),
    `ロール付与に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

Then('そのメンバーの権限一覧にROLE_EDITORの権限が含まれている', async ({ request, ctx }) => {
  const userId = ctx.roleUserId as number;
  const roles = await fetchRoles(request);
  const editorRole = roles.find((role) => role.roleName === 'ROLE_EDITOR');
  expect(editorRole, 'ROLE_EDITORがGET /api/rolesに見つかりません').toBeTruthy();

  const permissions = await fetchPermissions(request, userId);
  ctx.rolePermissionsAfterGrant = permissions;
  for (const permission of editorRole!.permissions) {
    expect(
      permissions,
      `ROLE_EDITOR付与後の権限一覧に ${permission} が含まれていません`
    ).toContain(permission);
  }
});

When('管理者がそのメンバーからROLE_EDITORロールを剥奪する', async ({ request, ctx }) => {
  const userId = ctx.roleUserId as number;
  const headers = await adminHeaders(request);
  const response = await request.delete(`/api/users/${userId}/roles/ROLE_EDITOR`, { headers });
  expect(
    response.ok(),
    `ロール剥奪に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

Then('そのメンバーの権限一覧はROLE_EDITOR付与前の状態に戻っている', async ({ request, ctx }) => {
  const userId = ctx.roleUserId as number;
  const roles = await fetchRoles(request);
  const editorRole = roles.find((role) => role.roleName === 'ROLE_EDITOR');
  const permissions = await fetchPermissions(request, userId);

  // ROLE_EDITOR固有の権限(閲覧者と重複しないもの)が消えていることを見る。
  // 新規メンバーは既定でROLE_VIEWER(USER_READ/POST_READ/SITE_READ)を持つため、
  // それらはROLE_EDITORにも含まれ、剥奪しても残り続ける(#956のシード参照)。
  const viewerOnlyPermissions = ['USER_READ', 'POST_READ', 'SITE_READ'];
  const editorSpecificPermissions = editorRole!.permissions.filter(
    (permission) => !viewerOnlyPermissions.includes(permission)
  );
  for (const permission of editorSpecificPermissions) {
    expect(
      permissions,
      `ROLE_EDITOR剥奪後も ${permission} が権限一覧に残っています`
    ).not.toContain(permission);
  }
});

// --------------------------------------------------------------- UIメニュー出し分け(親シナリオ13)

Given('ログイン可能なロール検証用のメンバーが登録されている', async ({ request, ctx }) => {
  await createRoleVerificationFixture(request, ctx);
  const email = ctx.roleEmail as string;
  const password = ctx.rolePassword as string;
  provisionLoginableKeycloakCredential(email, password);
});

When('そのメンバーとしてログインする', async ({ page, ctx }) => {
  await loginViaKeycloak(page, ctx.roleEmail as string, ctx.rolePassword as string);
});

When('そのメンバーがブラウザで再ログインする', async ({ page, ctx, $testInfo }) => {
  // このシナリオは初回ログインを含め最大3回ブラウザ経由でログインし直す。他の受け入れ
  // シナリオと並列実行されているとKeycloak/Next.jsの応答が遅れ、既定の30秒テストタイムアウトに
  // 収まらないことがある(degradation.steps.tsのSERVICE_CONTROL_TIMEOUT_MSと同じ理由)。
  $testInfo.setTimeout($testInfo.timeout + 30_000);
  const logoutButton = page.locator('button:has-text("ログアウト")');
  await expect(logoutButton).toBeVisible();
  await logoutButton.click();
  await page.waitForURL(
    (url) => url.pathname.startsWith('/login') || url.pathname.startsWith('/auth/realms/letsblog'),
    { timeout: 15000 }
  );
  await loginViaKeycloak(page, ctx.roleEmail as string, ctx.rolePassword as string);
});

Then('管理メニューが表示される', async ({ page }) => {
  await expect(page.getByRole('button', { name: '管理メニューを開く' })).toBeVisible({ timeout: 5000 });
});

Then('管理メニューが表示されない', async ({ page }) => {
  await expect(page.getByRole('button', { name: '管理メニューを開く' })).toHaveCount(0);
});

When('管理者がそのメンバーをadminへ昇格させる', async ({ request, ctx }) => {
  const userId = ctx.roleUserId as number;
  const headers = await adminHeaders(request);
  const response = await request.patch(`/api/users/${userId}`, {
    headers,
    data: { role: 'admin' },
  });
  expect(
    response.ok(),
    `admin昇格に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

When('管理者がそのメンバーをuserへ降格させる', async ({ request, ctx }) => {
  const userId = ctx.roleUserId as number;
  const headers = await adminHeaders(request);
  const response = await request.patch(`/api/users/${userId}`, {
    headers,
    data: { role: 'user' },
  });
  expect(
    response.ok(),
    `user降格に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

// --------------------------------------------------------------- 後片付け

After({ tags: '@identity' }, async ({ ctx, request }) => {
  const userId = ctx.roleUserId as number | undefined;
  if (userId === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  await request.delete(`/api/users/${userId}`, { headers });
});
