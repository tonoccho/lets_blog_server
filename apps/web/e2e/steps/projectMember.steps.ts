import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  createFixtureProject,
  deleteFixtureProject,
  expect,
  fetchAccessToken,
} from '../support';

/**
 * プロジェクトメンバーの追加・変更・除外のステップ定義(issue #1164 / AT-4-7、
 * 親issue #930のシナリオ14・15・16を引き取る子issue)。
 *
 * `roleManagement.steps.ts`(issue #1163)などと同様、ステップ定義ファイルは兄弟issueと
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
 * (`scripts/seed-acceptance-env.sh` 2/3手順と同じ内容。`roleManagement.steps.ts`と同型)。
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
    '-s', 'lastName=ProjectMember',
  ]);
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

async function memberHeaders(request: APIRequestContext, ctx: Record<string, unknown>): Promise<Record<string, string>> {
  const email = ctx.pmMemberEmail as string;
  const password = ctx.pmMemberPassword as string;
  const token = await fetchAccessToken(request, email, password);
  return { Authorization: `Bearer ${token}` };
}

type ProjectUserItem = { userId: number; wpRole: string };

async function fetchProjectUsers(
  request: APIRequestContext,
  projectId: number
): Promise<ProjectUserItem[]> {
  const headers = await adminHeaders(request);
  const response = await request.get(`/api/projects/${projectId}/users`, { headers });
  expect(
    response.ok(),
    `プロジェクトメンバー一覧の取得に失敗しました (status=${response.status()})`
  ).toBe(true);
  return (await response.json()) as ProjectUserItem[];
}

async function addMemberToProject(
  request: APIRequestContext,
  projectId: number,
  userId: number,
  wpRole: string
): Promise<void> {
  const headers = await adminHeaders(request);
  const response = await request.post(`/api/projects/${projectId}/users`, {
    headers,
    data: { userId, wpRole },
  });
  expect(
    response.ok(),
    `プロジェクトメンバーの追加に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

// --------------------------------------------------------------- 背景

Given('検証用のプロジェクトがある', async ({ request, ctx }) => {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const project = await createFixtureProject(request, token, 'at4-7-pm');
  ctx.pmProjectId = project.id;
});

Given('ログイン可能なプロジェクトメンバー検証用のアカウントが登録されている', async ({ request, ctx }) => {
  const suffix = uniqueSuffix();
  const email = `e2e-1164-member-${suffix}@example.com`;
  const password = `E2e1164Member!${suffix}`;

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

  provisionLoginableKeycloakCredential(email, password);

  ctx.pmMemberId = userId;
  ctx.pmMemberEmail = email;
  ctx.pmMemberPassword = password;
});

// --------------------------------------------------------------- 追加・閲覧(親シナリオ14)

Given('管理者がそのメンバーをプロジェクトへauthorとして追加している', async ({ request, ctx }) => {
  const projectId = ctx.pmProjectId as number;
  const userId = ctx.pmMemberId as number;
  await addMemberToProject(request, projectId, userId, 'author');
});

When('管理者がそのメンバーをプロジェクトへauthorとして追加する', async ({ request, ctx }) => {
  const projectId = ctx.pmProjectId as number;
  const userId = ctx.pmMemberId as number;
  await addMemberToProject(request, projectId, userId, 'author');
});

Then('そのメンバーはそのプロジェクトを閲覧できる', async ({ request, ctx }) => {
  const projectId = ctx.pmProjectId as number;
  const headers = await memberHeaders(request, ctx);
  const response = await request.get(`/api/projects/${projectId}`, { headers });
  expect(
    response.status(),
    `メンバーなのにプロジェクトを閲覧できません (status=${response.status()}): ${await response.text()}`
  ).toBe(200);
  const body = (await response.json()) as { id: number };
  expect(body.id).toBe(projectId);
});

// --------------------------------------------------------------- 役割変更(親シナリオ15)

When('管理者がそのメンバーの役割をeditorへ変更する', async ({ request, ctx }) => {
  const projectId = ctx.pmProjectId as number;
  const userId = ctx.pmMemberId as number;
  const headers = await adminHeaders(request);
  const response = await request.put(`/api/projects/${projectId}/users/${userId}`, {
    headers,
    data: { wpRole: 'editor' },
  });
  expect(
    response.ok(),
    `役割変更に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

Then('そのプロジェクトのメンバー一覧でそのメンバーの役割がeditorになっている', async ({ request, ctx }) => {
  const projectId = ctx.pmProjectId as number;
  const userId = ctx.pmMemberId as number;
  const members = await fetchProjectUsers(request, projectId);
  const member = members.find((item) => item.userId === userId);
  expect(member, `メンバー一覧にuserId=${userId}が見つかりません`).toBeTruthy();
  expect(member!.wpRole).toBe('editor');
});

// --------------------------------------------------------------- 除外(親シナリオ16)

When('管理者がそのメンバーをプロジェクトから外す', async ({ request, ctx }) => {
  const projectId = ctx.pmProjectId as number;
  const userId = ctx.pmMemberId as number;
  const headers = await adminHeaders(request);
  const response = await request.delete(`/api/projects/${projectId}/users/${userId}`, { headers });
  expect(
    response.ok(),
    `メンバー除外に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

Then('そのメンバーはそのプロジェクトへアクセスできない', async ({ request, ctx }) => {
  const projectId = ctx.pmProjectId as number;
  const headers = await memberHeaders(request, ctx);
  const response = await request.get(`/api/projects/${projectId}`, { headers });
  expect(
    response.status(),
    `除外されたはずのメンバーがプロジェクトを閲覧できています (status=${response.status()})`
  ).toBe(403);
});

// --------------------------------------------------------------- 後片付け

After({ tags: '@identity' }, async ({ ctx, request }) => {
  const projectId = ctx.pmProjectId as number | undefined;
  const memberId = ctx.pmMemberId as number | undefined;
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const headers = { Authorization: `Bearer ${token}` };

  if (memberId !== undefined) {
    await request.delete(`/api/users/${memberId}`, { headers });
  }
  if (projectId !== undefined) {
    await deleteFixtureProject(request, token, projectId);
  }
});
