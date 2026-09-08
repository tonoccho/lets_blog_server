import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Step, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * ユーザーの無効化・再有効化(issue #1158 / AT-4-2、親issue #930のシナリオ3・4を引き取る
 * 子issue)のステップ定義。
 *
 * ## なぜ docker exec で kcadm を呼ぶのか
 *
 * `POST /api/users`(管理者によるユーザー作成)はローカルDBとKeycloakの双方にユーザーを
 * 作るが、**Keycloak側のパスワードまでは設定しない**
 * (`UserService#create` → `keycloakAdminClient.createUser(email, null, null, false)`)。
 * 管理者がPATCH /api/users/{id}でpasswordを送っても、更新されるのはローカルの
 * password_hashだけで、実際のログインに使われるKeycloak側の資格情報には反映されない。
 * そのため「無効化されたアカウントでログインできない」を検証するには、まず実際にログイン
 * できる状態を作る必要があるが、それを行う公開APIはPOST /api/auth/setup(初回セットアップ、
 * 一度きり)以外に存在しない。
 *
 * `scripts/seed-acceptance-env.sh`(2/3手順)が最初の管理者に対して行っているのと同じ手順
 * ——`docker exec`でコンテナ内の`kcadm.sh`を叩き、パスワードを即時設定し、VERIFY_PROFILE
 * (required action)が要求するfirstName/lastNameを埋める——を、検証用アカウントにも行う。
 * `apps/web/e2e/helpers.ts`の`composeServiceControl`が同様に`docker`をローカルサブ
 * プロセスとして呼ぶ前例に倣う。
 *
 * ## なぜ@destructiveを付けないか(実装ノートへの回答)
 *
 * 無効化するのはこのシナリオが自分で作った使い捨てアカウントだけであり、共有のE2E固定
 * アカウント(`e2e-*@letsblog.local`)には一切触れない。他のシナリオが使っているアカウントの
 * ログインを妨げないため、`atDestructive`が隔離しようとしている「共有アカウントを無効化して
 * 無関係な並列シナリオを巻き添えにする」状況(`apps/web/playwright.config.ts`のコメント)には
 * 当たらない。
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
 * (`scripts/seed-acceptance-env.sh` 2/3手順と同じ内容)。
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
    '-s', 'lastName=Deactivation',
  ]);
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

/** 検証用アカウントでのパスワードグラントの結果(ログイン可否の判定に使う)。 */
async function attemptLogin(
  request: APIRequestContext,
  email: string,
  password: string
): Promise<{ ok: boolean; status: number; body: { access_token?: string; error?: string; error_description?: string } }> {
  const response = await request.post('/auth/realms/letsblog/protocol/openid-connect/token', {
    form: {
      grant_type: 'password',
      client_id: 'letsblog-e2e',
      username: email,
      password,
    },
  });
  return { ok: response.ok(), status: response.status(), body: (await response.json()) as { access_token?: string; error?: string; error_description?: string } };
}

async function deactivateAccount(request: APIRequestContext, ctx: Record<string, unknown>): Promise<void> {
  const headers = await adminHeaders(request);
  const userId = ctx.userDeactivationUserId as number;
  const response = await request.post(`/api/users/${userId}/deactivate`, { headers });
  expect(response.ok(), `無効化に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
}

// --------------------------------------------------------------- セットアップ

Given('一意なメールとパスワードを持つ検証用アカウントを作成する', async ({ request, ctx }) => {
  const suffix = uniqueSuffix();
  const email = `e2e-1158-deactivation-${suffix}@example.com`;
  const password = `E2e1158Deact!${suffix}`;

  const headers = await adminHeaders(request);
  const created = await request.post('/api/users', {
    headers,
    data: { email, password, role: 'user' },
  });
  expect(
    created.ok(),
    `検証用アカウントの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const userId = ((await created.json()) as { id: number }).id;

  // 上のUserCreateRequest.passwordはローカルDBにしか反映されないため、実際にログインできる
  // ようにKeycloak側の資格情報を別途整える(ファイル冒頭のコメント参照)。
  provisionLoginableKeycloakCredential(email, password);

  ctx.userDeactivationEmail = email;
  ctx.userDeactivationPassword = password;
  ctx.userDeactivationUserId = userId;
});

Given('そのアカウントでログインできる', async ({ request, ctx }) => {
  const email = ctx.userDeactivationEmail as string;
  const password = ctx.userDeactivationPassword as string;
  const result = await attemptLogin(request, email, password);
  expect(
    result.ok,
    `検証用アカウントのログインに失敗しました(前提が崩れています): ${JSON.stringify(result.body)}`
  ).toBe(true);
  expect(result.body.access_token).toBeTruthy();
});

// --------------------------------------------------------------- 無効化・再有効化

Step('管理者がその検証用アカウントを無効化する', async ({ request, ctx }) => {
  await deactivateAccount(request, ctx);
});

Step('管理者がその検証用アカウントを無効化している', async ({ request, ctx }) => {
  await deactivateAccount(request, ctx);
});

When('管理者がその検証用アカウントを再有効化する', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const userId = ctx.userDeactivationUserId as number;
  const response = await request.post(`/api/users/${userId}/reactivate`, { headers });
  expect(response.ok(), `再有効化に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
});

// --------------------------------------------------------------- 検証

Then('その検証用アカウントではログインできない', async ({ request, ctx }) => {
  const email = ctx.userDeactivationEmail as string;
  const password = ctx.userDeactivationPassword as string;
  const result = await attemptLogin(request, email, password);
  expect(result.ok, '無効化したはずのアカウントでログインできてしまった').toBe(false);
  expect(result.status).toBeGreaterThanOrEqual(400);
  expect(result.body.access_token).toBeUndefined();
});

Then('その検証用アカウントで再びログインできる', async ({ request, ctx }) => {
  const email = ctx.userDeactivationEmail as string;
  const password = ctx.userDeactivationPassword as string;
  const result = await attemptLogin(request, email, password);
  expect(result.ok, `再有効化後のログインに失敗した: ${JSON.stringify(result.body)}`).toBe(true);
  expect(result.body.access_token).toBeTruthy();
});

// --------------------------------------------------------------- 後片付け

After({ tags: '@identity' }, async ({ ctx, request }) => {
  const userId = ctx.userDeactivationUserId as number | undefined;
  if (userId === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  // 削除はUserService#deleteでローカル・Keycloak双方から消える。既に削除済みでも404を無視する。
  await request.delete(`/api/users/${userId}`, { headers });
});
