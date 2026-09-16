import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import type { APIRequestContext } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  expect,
  fetchAccessToken,
  loginViaKeycloak,
} from '../support';

/**
 * issue #1259: 個人設定のタイムゾーンを任意の上書きにする、のステップ定義。
 *
 * `userManagement.steps.ts` / `avatarUpload.steps.ts` と同様、ステップ定義ファイルは
 * 兄弟issueと相乗りしない方針のため、必要なヘルパーはこのファイル内に閉じて持つ。
 */

const KEYCLOAK_CONTAINER = 'lbs-keycloak';
const KEYCLOAK_REALM = 'letsblog';
const KCADM_BIN = '/opt/keycloak/bin/kcadm.sh';

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
  return execFileSync('docker', ['exec', KEYCLOAK_CONTAINER, KCADM_BIN, ...args], {
    encoding: 'utf-8',
    timeout: 30_000,
  });
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
  await page.getByRole('button', { name: '個人設定' }).click();
});

When(/^タイムゾーンで「ブラウザに従う\(未設定\)」を選んで保存する$/, async ({ page }) => {
  await page.locator('[data-testid="timezone-select"]').selectOption('');
  await page.locator('[data-testid="preferences-save"]').click();
  await expect(page.locator('[data-testid="preferences-success"]')).toBeVisible();
});

Then(/^画面を再読み込みしても「ブラウザに従う\(未設定\)」が選択されている$/, async ({ page }) => {
  await page.reload();
  await page.getByRole('button', { name: '個人設定' }).click();
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
