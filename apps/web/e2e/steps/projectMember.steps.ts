import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { APIRequestContext, Page } from '@playwright/test';
import { kcadm, kcadmLogin, KEYCLOAK_REALM } from '../kcadm';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  createFixtureProject,
  deleteFixtureProject,
  expect,
  fetchAccessToken,
  loginAsAdmin,
} from '../support';

/**
 * プロジェクトメンバーの追加・変更・除外のステップ定義(issue #1164 / AT-4-7、
 * 親issue #930のシナリオ14・15・16を引き取る子issue)。
 *
 * `roleManagement.steps.ts`(issue #1163)などと同様、ステップ定義ファイルは兄弟issueと
 * 相乗りしない方針(issue本文参照)のため、必要なヘルパーはこのファイル内に閉じて持つ
 * (Keycloak側の資格情報整え(kcadm経由)は共有モジュール`../kcadm`、issue #1328)。
 */

/** リポジトリルート(apps/web/e2e/steps から4階層上)。wp-cliの実行ディレクトリ(下記`wpCli`)に使う。 */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

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

// --------------------------------------------------------------- ユーザー情報同期(issue #1242)

/** issue #1176(authors.feature)のensureManagedSiteと同じ「固定siteKeyで冪等に用意し、実行をまたいで再利用する」パターン。 */
const SYNC_PROBE_SITE_KEY = 'at1242syncprobe';
const SYNC_PROBE_SITE_ADMIN_USER = 'at1242syncadmin';
const PROVISION_TIMEOUT_MS = 600_000;

/** `WordPressSiteProvisioningService#normalizeSlug` と同じ正規化(publishAuthor.steps.tsのwpSlugと同型)。 */
function wpSlug(siteKey: string): string {
  return siteKey.toLowerCase().replace(/[^a-z0-9-]/g, '-');
}

/** WordPress コンテナで wp-cli を実行する(publishAuthor.steps.tsのwpCliと同型)。 */
function wpCli(slug: string, command: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'wordpress', 'sh', '-c', `cd /var/www/html/sites/${slug} && wp --allow-root ${command}`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 180_000 }
  ).trim();
}

function shellQuote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

interface WpUser {
  ID: string;
  user_email: string;
}

function findWpUserByEmail(slug: string, email: string): WpUser[] {
  const output = wpCli(slug, `user list --search=${shellQuote(email)} --fields=ID,user_email --format=json`);
  const users = (JSON.parse(output || '[]') as { ID: number | string; user_email: string }[]) ?? [];
  return users
    .filter((user) => user.user_email.toLowerCase() === email.toLowerCase())
    .map((user) => ({ ID: String(user.ID), user_email: user.user_email }));
}

interface SiteFixture {
  id: number;
  siteKey: string;
  name: string;
}

/** ユーザー情報同期検証用のManagedWordPressサイトを冪等に用意する(authors.steps.tsのensureManagedSiteと同型)。 */
async function ensureSyncProbeSite(request: APIRequestContext): Promise<SiteFixture> {
  const headers = await adminHeaders(request);
  const list = await request.get('/api/sites', { headers });
  expect(list.ok(), `サイト一覧の取得に失敗しました (status=${list.status()})`).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find((site) => site.siteKey === SYNC_PROBE_SITE_KEY);
  if (existing) {
    return existing;
  }
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: 'AT-1242 sync probe site',
      siteKey: SYNC_PROBE_SITE_KEY,
      title: 'AT-1242 Sync Probe',
      adminUser: SYNC_PROBE_SITE_ADMIN_USER,
      adminEmail: 'at1242-sync-probe@letsblog.local',
      adminPassword: 'At1242Sync#Passw0rd1',
    },
    timeout: PROVISION_TIMEOUT_MS,
  });
  if (created.status() === 409) {
    // publishAuthor.steps.tsのadoptExistingManagedSiteと同じ理由(取り残しの取り込み)。
    const adopted = await request.post('/api/sites/managed-wordpress/adopt', {
      headers,
      data: { name: 'AT-1242 sync probe site', siteKey: SYNC_PROBE_SITE_KEY, adminUser: SYNC_PROBE_SITE_ADMIN_USER },
    });
    expect(adopted.ok(), `既存WordPressの取り込みに失敗しました (status=${adopted.status()}): ${await adopted.text()}`).toBe(true);
    return (await adopted.json()) as SiteFixture;
  }
  expect(created.ok(), `マネージドWordPressの構築に失敗しました (status=${created.status()}): ${await created.text()}`).toBe(true);
  return (await created.json()) as SiteFixture;
}

/** 到達不能なSSHホストを指す既存WordPressサイトを登録する(siteRegistration.steps.tsのregisterFixtureSiteと同型)。 */
async function registerUnreachableSite(request: APIRequestContext, headers: Record<string, string>): Promise<SiteFixture> {
  const suffix = uniqueSuffix();
  const siteKey = `e2e-1242-unreachable-${suffix}`;
  const name = `E2E 1242 unreachable ${suffix}`;
  const response = await request.post('/api/sites', {
    headers,
    data: {
      name,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'SSH',
        baseUrl: 'http://wrong.invalid',
        sshHost: 'wrong.invalid',
        sshUser: 'nouser',
        wpPath: '/nowhere',
        sshPrivateKeyPem:
          '-----BEGIN OPENSSH PRIVATE KEY-----\ne2e-1242-fixture-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----',
      },
    },
  });
  expect(response.ok(), `疎通不能サイトの登録に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  const body = (await response.json()) as { id: number };
  return { id: body.id, siteKey, name };
}

async function bindSiteToProject(
  request: APIRequestContext, headers: Record<string, string>, projectId: number, environment: string, siteId: number
): Promise<void> {
  const response = await request.post(`/api/projects/${projectId}/environments`, {
    headers,
    data: { environment, siteId },
  });
  expect(
    response.ok(),
    `環境紐付けに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

/** プロジェクト詳細の「メンバー」タブを開く(media.steps.tsのopenProjectTabと同型)。 */
async function openMembersTab(page: Page, projectId: number): Promise<void> {
  await page.goto(`/projects/${projectId}`, { waitUntil: 'commit' });
  await page.waitForLoadState('load');
  const tab = page.getByRole('button', { name: 'メンバー', exact: true });
  await expect(tab).toBeVisible({ timeout: 30_000 });
  // `Tabs.tsx`はクライアントコンポーネントで、タブ切替はReactのonClickに依存する。SSR直後は
  // ハイドレーション未完了のことがあり、その状態でクリックしてもハンドラが付く前に
  // イベントが素通りしてタブが切り替わらないことがある(#1242で実際に観測)。
  // ハイドレーション完了を明示的に待つ手段が無いため、「メンバー一覧の見出しが出るまで
  // クリックし直す」形で吸収する。
  await expect(async () => {
    await tab.click();
    await expect(page.getByRole('columnheader', { name: 'メールアドレス' })).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
}

interface UserProfile {
  firstName: string | null;
  lastName: string | null;
  displayName: string | null;
  nickname: string | null;
  websiteUrl: string | null;
  bio: string | null;
  locale: string | null;
  avatarUrl: string | null;
  department: string | null;
  position: string | null;
  socialLinks: unknown;
  customLinks: unknown;
}

async function updateMemberProfile(
  request: APIRequestContext,
  memberId: number,
  changes: Partial<Pick<UserProfile, 'displayName' | 'bio' | 'websiteUrl'>>
): Promise<void> {
  const headers = await adminHeaders(request);
  const current = await request.get(`/api/users/${memberId}`, { headers });
  expect(current.ok(), `プロフィール取得に失敗しました (status=${current.status()})`).toBe(true);
  const profile = (await current.json()) as UserProfile;
  const body: UserProfile = { ...profile, ...changes };
  const updated = await request.put(`/api/users/${memberId}`, { headers, data: body });
  expect(
    updated.ok(),
    `プロフィール更新に失敗しました (status=${updated.status()}): ${await updated.text()}`
  ).toBe(true);
}

interface SyncSiteResult {
  siteId: number;
  siteKey: string;
  siteName: string;
  success: boolean;
  errorMessage: string | null;
}

// ---- AC1: ボタンの表示 ----

When('管理者としてそのプロジェクトのメンバー画面を開く', async ({ ctx, page }) => {
  await loginAsAdmin(page);
  await openMembersTab(page, ctx.pmProjectId as number);
});

Then('そのメンバーの行に「ユーザー情報を同期」ボタンが表示される', async ({ ctx, page }) => {
  const email = ctx.pmMemberEmail as string;
  const row = page.locator('tr', { hasText: email });
  await expect(row.getByRole('button', { name: 'ユーザー情報を同期' })).toBeVisible();
});

// ---- AC4: 非管理者は拒否される ----

When('一般ユーザーがそのメンバーのユーザー情報同期を試みる', async ({ ctx, request }) => {
  const projectId = ctx.pmProjectId as number;
  const memberId = ctx.pmMemberId as number;
  const token = await fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
  // userAuthorization.steps.tsの`Then('権限不足として拒否される', ...)`が読む共通のctxキーを使う。
  ctx.authzResponse = await request.post(`/api/projects/${projectId}/users/${memberId}/sync`, {
    headers: { Authorization: `Bearer ${token}` },
  });
});

// ---- AC2: プロフィールの反映 ----

Given('ユーザー情報同期検証用のWordPress環境がそのプロジェクトに紐づいている', async ({ ctx, request }) => {
  const site = await ensureSyncProbeSite(request);
  const headers = await adminHeaders(request);
  await bindSiteToProject(request, headers, ctx.pmProjectId as number, 'test', site.id);
  ctx.pmSyncSiteSlug = wpSlug(site.siteKey);
});

When('そのメンバーの表示名・自己紹介・URLを変更する', async ({ ctx, request }) => {
  const suffix = uniqueSuffix();
  const displayName = `E2E Sync Display ${suffix}`;
  const bio = `E2E sync bio ${suffix}`;
  const websiteUrl = `https://example.com/e2e-1242-sync-${suffix}`;
  await updateMemberProfile(request, ctx.pmMemberId as number, { displayName, bio, websiteUrl });
  ctx.pmSyncDisplayName = displayName;
  ctx.pmSyncBio = bio;
  ctx.pmSyncWebsiteUrl = websiteUrl;
});

When('管理者がそのメンバーのユーザー情報同期を実行する', async ({ ctx, request }) => {
  const projectId = ctx.pmProjectId as number;
  const memberId = ctx.pmMemberId as number;
  const headers = await adminHeaders(request);
  const response = await request.post(`/api/projects/${projectId}/users/${memberId}/sync`, { headers });
  expect(
    response.ok(),
    `ユーザー情報同期の実行に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.pmSyncResults = (await response.json()) as SyncSiteResult[];
});

Then('紐づくWordPress環境の著者情報がそのメンバーの最新プロフィールと一致する', async ({ ctx }) => {
  const slug = ctx.pmSyncSiteSlug as string;
  const email = ctx.pmMemberEmail as string;
  const wpUsers = findWpUserByEmail(slug, email);
  expect(wpUsers, `WordPress側に ${email} に対応するユーザーが見つかりません`).toHaveLength(1);
  const userId = wpUsers[0].ID;

  // descriptionはwp_usersの列ではなくusermeta側にあるため、`user get --field=`ではなく
  // `user meta get`で読む(`user get --field=description`は"Invalid field"になる)。
  expect(wpCli(slug, `user get ${userId} --field=display_name`)).toBe(ctx.pmSyncDisplayName);
  expect(wpCli(slug, `user meta get ${userId} description`)).toBe(ctx.pmSyncBio);
  expect(wpCli(slug, `user get ${userId} --field=user_url`)).toBe(ctx.pmSyncWebsiteUrl);
});

// ---- AC3: 部分失敗 ----

Given('ユーザー情報同期検証用のWordPress環境と疎通不能な環境がそのプロジェクトに紐づいている', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);

  const okSite = await ensureSyncProbeSite(request);
  await bindSiteToProject(request, headers, ctx.pmProjectId as number, 'test', okSite.id);
  ctx.pmSyncSiteSlug = wpSlug(okSite.siteKey);

  const ngSite = await registerUnreachableSite(request, headers);
  await bindSiteToProject(request, headers, ctx.pmProjectId as number, 'production', ngSite.id);
  ctx.pmUnreachableSiteId = ngSite.id;
  ctx.pmUnreachableSiteName = ngSite.name;
});

When('管理者としてそのプロジェクトのメンバー画面でそのメンバーのユーザー情報同期を実行する', async ({ ctx, page }) => {
  await loginAsAdmin(page);
  await openMembersTab(page, ctx.pmProjectId as number);
  const email = ctx.pmMemberEmail as string;
  const row = page.locator('tr', { hasText: email });
  await row.getByRole('button', { name: 'ユーザー情報を同期' }).click();
  // ボタン押下は非同期(サーバーアクション)で、結果が描画されるまで「処理中…」表示が続く。
  // 実際のWordPress反映(wp-cli)を検証する前に、開始(「処理中…」表示)→完了(元の表示に戻る)を
  // 順に待つ必要がある(押した直後に即座に確認すると、まだ結果が返っていないうちに検証してしまう)。
  await expect(row.getByText('処理中…').first()).toBeVisible({ timeout: 10_000 });
  await expect(row.getByRole('button', { name: 'ユーザー情報を同期' })).toBeVisible({ timeout: 60_000 });
  ctx.pmSyncRow = row;
});

Then('疎通可能なWordPress環境の著者情報は同期されている', async ({ ctx }) => {
  const slug = ctx.pmSyncSiteSlug as string;
  const email = ctx.pmMemberEmail as string;
  const wpUsers = findWpUserByEmail(slug, email);
  expect(wpUsers, `WordPress側に ${email} に対応するユーザーが見つかりません`).toHaveLength(1);
});

Then('疎通不能な環境については失敗した理由が画面に表示される', async ({ ctx }) => {
  const row = ctx.pmSyncRow as ReturnType<Page['locator']>;
  const siteName = ctx.pmUnreachableSiteName as string;
  await expect(row.getByText(siteName, { exact: false })).toBeVisible({ timeout: 30_000 });
  await expect(row.getByText(/失敗|エラー/)).toBeVisible();
});

// ---- AC5: 監査ログ ----

async function listAuditLogsByAction(
  request: APIRequestContext, token: string
): Promise<{ id: number; action: string; resourceId: number | null; changes: string | null }[]> {
  const response = await request.get('/api/audit-logs?page=0&size=200&sort=createdAt,desc', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.ok(), `監査ログの取得に失敗しました (status=${response.status()})`).toBe(true);
  return ((await response.json()) as { content: { id: number; action: string; resourceId: number | null; changes: string | null }[] }).content;
}

Given('ユーザー情報同期検証用に現時点の監査ログを控えておく', async ({ ctx, request }) => {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const logs = await listAuditLogsByAction(request, token);
  ctx.pmKnownAuditIds = new Set(logs.map((entry) => entry.id));
});

Then('ユーザー情報同期の監査ログが記録されるまで待つ', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + 60_000);
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const projectId = ctx.pmProjectId as number;
  const memberId = ctx.pmMemberId as number;
  const known = ctx.pmKnownAuditIds as Set<number>;

  const deadline = Date.now() + 60_000;
  let found: { id: number; action: string; resourceId: number | null; changes: string | null } | null = null;
  for (;;) {
    const logs = await listAuditLogsByAction(request, token);
    found = logs.find(
      (entry) => !known.has(entry.id) && entry.action === 'PROJECT_USER_SYNCED' && entry.resourceId === projectId
    ) ?? null;
    if (found || Date.now() >= deadline) {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 2_000));
  }

  expect(found, 'ユーザー情報同期の監査ログが記録されていません').toBeTruthy();
  expect(found!.changes ?? '', `監査ログのchangesに対象メンバー(${memberId})が含まれません`).toContain(String(memberId));
});

// ---- issue #1302: 同期失敗時の環境名と理由 ----

Given('疎通不能な環境だけがそのプロジェクトに紐づいている', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const ngSite = await registerUnreachableSite(request, headers);
  await bindSiteToProject(request, headers, ctx.pmProjectId as number, 'production', ngSite.id);
  ctx.pmUnreachableSiteId = ngSite.id;
  ctx.pmUnreachableSiteName = ngSite.name;
});

/** 失敗理由の既存文言(PublishingServiceClient#provisionAuthor)。サイト名と併せて両方が含まれることを確かめる。 */
const FAILURE_REASON = '著者プロビジョニング';

When('管理者がそのメンバーをプロジェクトへauthorとして追加しようとする', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  ctx.pmFailedResponse = await request.post(`/api/projects/${ctx.pmProjectId}/users`, {
    headers,
    data: { userId: ctx.pmMemberId, wpRole: 'author' },
  });
});

When('管理者がそのメンバーの役割をeditorへ変更しようとする', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  ctx.pmFailedResponse = await request.put(`/api/projects/${ctx.pmProjectId}/users/${ctx.pmMemberId}`, {
    headers,
    data: { wpRole: 'editor' },
  });
});

Then('502で拒否され、メッセージに疎通不能な環境の名前と失敗の理由が含まれる', async ({ ctx }) => {
  const response = ctx.pmFailedResponse as Awaited<ReturnType<APIRequestContext['post']>>;
  expect(response.status()).toBe(502);
  const body = await response.text();
  expect(body, '応答に失敗した環境の名前が含まれていません').toContain(ctx.pmUnreachableSiteName as string);
  expect(body, '応答に失敗の理由が含まれていません').toContain(FAILURE_REASON);
});

Then('そのプロジェクトのメンバー一覧にそのメンバーは含まれない', async ({ request, ctx }) => {
  const members = await fetchProjectUsers(request, ctx.pmProjectId as number);
  expect(members.find((item) => item.userId === ctx.pmMemberId)).toBeUndefined();
});

Then('そのプロジェクトのメンバー一覧でそのメンバーの役割がauthorのままである', async ({ request, ctx }) => {
  const members = await fetchProjectUsers(request, ctx.pmProjectId as number);
  const member = members.find((item) => item.userId === ctx.pmMemberId);
  expect(member, 'メンバー一覧にそのメンバーが見つかりません').toBeTruthy();
  expect(member!.wpRole).toBe('author');
});

When('管理者としてそのプロジェクトのメンバー画面でそのメンバーの役割をeditorへ変更する', async ({ ctx, page }) => {
  await loginAsAdmin(page);
  await openMembersTab(page, ctx.pmProjectId as number);
  const row = page.locator('tr', { hasText: ctx.pmMemberEmail as string });
  await row.getByRole('combobox').selectOption('editor');
  ctx.pmSyncRow = row;
});

Then('そのメンバーの行に疎通不能な環境の名前と失敗の理由が表示される', async ({ ctx }) => {
  const row = ctx.pmSyncRow as ReturnType<Page['locator']>;
  await expect(row.getByText(ctx.pmUnreachableSiteName as string, { exact: false })).toBeVisible({ timeout: 60_000 });
  await expect(row.getByText(FAILURE_REASON, { exact: false })).toBeVisible();
});

When('管理者としてそのプロジェクトのメンバー画面でそのメンバーをauthorとして追加する', async ({ ctx, page }) => {
  await loginAsAdmin(page);
  await openMembersTab(page, ctx.pmProjectId as number);
  const form = page.locator('form').filter({ hasText: 'ユーザーを追加' });
  await form.locator('select[name="userId"]').selectOption({ label: ctx.pmMemberEmail as string });
  await form.locator('select[name="wpRole"]').selectOption('author');
  await form.getByRole('button', { name: '追加', exact: true }).click();
  ctx.pmAddForm = form;
});

Then('追加フォームに疎通不能な環境の名前と失敗の理由が表示される', async ({ ctx }) => {
  const form = ctx.pmAddForm as ReturnType<Page['locator']>;
  await expect(form.getByText(ctx.pmUnreachableSiteName as string, { exact: false })).toBeVisible({ timeout: 60_000 });
  await expect(form.getByText(FAILURE_REASON, { exact: false })).toBeVisible();
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

  const unreachableSiteId = ctx.pmUnreachableSiteId as number | undefined;
  if (unreachableSiteId !== undefined) {
    await request.delete(`/api/sites/${unreachableSiteId}`, { headers });
  }

  // ensureSyncProbeSiteが作るManagedWordPressサイト自体は、authors.steps.tsのensureManagedSiteと
  // 同じ理由(固定siteKeyで冪等に再利用する)で削除しない。作成した検証用WordPressユーザーだけ後片付けする。
  const slug = ctx.pmSyncSiteSlug as string | undefined;
  const email = ctx.pmMemberEmail as string | undefined;
  if (slug && email) {
    try {
      const wpUsers = findWpUserByEmail(slug, email);
      for (const wpUser of wpUsers) {
        wpCli(slug, `user delete ${wpUser.ID} --yes`);
      }
    } catch {
      // 後片付けの失敗としては扱わない(publishAuthor.steps.tsと同じ方針)。
    }
  }
});
