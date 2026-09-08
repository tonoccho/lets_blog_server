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
 * 記事の著者マッピングと著者の自動プロビジョニング(issue #1176 / AT-6-6)のステップ定義。
 *
 * 兄弟issue(#932系列の子issue群)とステップ定義ファイルを共有しない方針
 * (`publishTaxonomy.steps.ts`・`siteAdoption.steps.ts`と同様。相乗りしない)のため、
 * 必要なヘルパーはこのファイル内に閉じて持つ。
 *
 * ## 専用サイトを冪等に用意する理由
 *
 * 著者マッピングの正しさは実際のWordPressのユーザー・投稿の状態(wp-cli)を見ないと
 * 確かめられない。#1167のプロビジョニング済みサイト共有フィクスチャ
 * (`site-provisioning.steps.ts`)はサイトの識別子だけを永続化しWordPress管理者の認証情報は
 * 残さないため、後始末(作成した投稿・著者アカウントの削除)にwp-cliで直接アクセスする
 * 必要がある本ファイルには使えない。そこで`publishTaxonomy.steps.ts`(#1174)と同じ
 * 「固定siteKeyで冪等に用意し、実行をまたいで再利用する」パターンを踏襲する。
 */

/** リポジトリルート(apps/web/e2e/steps から4階層上)。 */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

/** 著者マッピング検証用のサイト。冪等に用意し、実行をまたいで再利用する。 */
const AUTHOR_SITE_KEY = 'at66authorprobe';
const AUTHOR_SITE_ADMIN_USER = 'at66authoradmin';

/** WordPress自動構築の待ち上限。分単位でかかりうる。 */
const PROVISION_TIMEOUT_MS = 600_000;

const KEYCLOAK_CONTAINER = 'lbs-keycloak';
const KEYCLOAK_REALM = 'letsblog';
const KCADM_BIN = '/opt/keycloak/bin/kcadm.sh';

interface SiteFixture {
  id: number;
  siteKey: string;
}

interface PostPublishResponse {
  wpPostId: string;
  wpPostUrl: string;
  status: string;
}

/** `WordPressSiteProvisioningService#normalizeSlug` と同じ正規化。 */
function wpSlug(siteKey: string): string {
  return siteKey.toLowerCase().replace(/[^a-z0-9-]/g, '-');
}

/**
 * WordPress コンテナで wp-cli を実行し、標準出力を返す。
 *
 * `docker compose exec` の cwd はコンテナの `WorkingDir` になるため、
 * サイトディレクトリへは `sh -c 'cd ... && ...'` で自分で移動する
 * (docs/ACCEPTANCE_TESTING.md §9「`working_dir` はマウント先にしない」)。
 */
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

/**
 * メールアドレスでWordPressユーザーを検索する(著者自動プロビジョニングと同じ検索基準)。
 *
 * wp-cliの`--format=json`はIDフィールドを数値として返すため、`post get --field=post_author`
 * (文字列)と比較できるよう、ここで文字列へ揃える(`publishTaxonomy.steps.ts`の
 * `findTermsByName`と同じ理由)。
 */
function findWpUserByEmail(slug: string, email: string): WpUser[] {
  const output = wpCli(slug, `user list --search=${shellQuote(email)} --fields=ID,user_email --format=json`);
  const users = (JSON.parse(output || '[]') as { ID: number | string; user_email: string }[]) ?? [];
  return users
    .filter((user) => user.user_email.toLowerCase() === email.toLowerCase())
    .map((user) => ({ ID: String(user.ID), user_email: user.user_email }));
}

/** 投稿のpost_authorをwp-cliで取得する。 */
function postAuthor(slug: string, postId: string): string {
  return wpCli(slug, `post get ${postId} --field=post_author`);
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await adminToken(request);
  return { Authorization: `Bearer ${token}` };
}

/** issue #765と同じ理由(並列実行時の衝突対策)でユニークな名前を作る。 */
function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

async function ensureManagedSite(request: APIRequestContext): Promise<SiteFixture> {
  const headers = await adminHeaders(request);
  const list = await request.get('/api/sites', { headers });
  expect(
    list.ok(),
    `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`
  ).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find((site) => site.siteKey === AUTHOR_SITE_KEY);
  if (existing) {
    return existing;
  }
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: 'AT6-6 author probe site',
      siteKey: AUTHOR_SITE_KEY,
      title: 'AT6-6 Author Probe',
      adminUser: AUTHOR_SITE_ADMIN_USER,
      adminEmail: 'at66-author-probe@letsblog.local',
      adminPassword: 'At66Author#Passw0rd1',
    },
    timeout: PROVISION_TIMEOUT_MS,
  });
  if (created.status() === 409) {
    // provision-agent側には既に実体があるがDBには未登録(中断した前回実行の取り残し、または
    // このシナリオ自体を@mode:serialなしで並列実行してしまった場合)。
    // site-adoption.steps.tsと同じ「取り込み」で救う(site-adoption.feature参照)。
    return adoptExistingManagedSite(request, headers);
  }
  expect(
    created.ok(),
    `マネージドWordPressの構築に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  return (await created.json()) as SiteFixture;
}

async function adoptExistingManagedSite(
  request: APIRequestContext,
  headers: Record<string, string>
): Promise<SiteFixture> {
  const adopted = await request.post('/api/sites/managed-wordpress/adopt', {
    headers,
    data: {
      name: 'AT6-6 author probe site',
      siteKey: AUTHOR_SITE_KEY,
      adminUser: AUTHOR_SITE_ADMIN_USER,
    },
  });
  expect(
    adopted.ok(),
    `既存WordPressの取り込みに失敗しました (status=${adopted.status()}): ${await adopted.text()}`
  ).toBe(true);
  return (await adopted.json()) as SiteFixture;
}

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
 * (`scripts/seed-acceptance-env.sh` 2/3手順と同じ内容。`projectMember.steps.ts`と同型)。
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
    '-s', 'lastName=AuthorProbe',
  ]);
}

// ------------------------------------------------------- 前提

Given('著者マッピング検証用のWordPressサイトがあり、プロジェクトに紐づいている', async ({ ctx, request }) => {
  const site = await ensureManagedSite(request);
  const token = await adminToken(request);
  const project = await createFixtureProject(request, token, 'at66-author');
  const headers = await adminHeaders(request);
  const bound = await request.post(`/api/projects/${project.id}/environments`, {
    headers,
    data: { environment: 'test', siteId: site.id },
  });
  expect(
    bound.ok(),
    `テスト環境へのサイト紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`
  ).toBe(true);

  ctx.authorSiteKey = site.siteKey;
  ctx.authorSiteSlug = wpSlug(site.siteKey);
  ctx.authorProjectId = project.id;
});

// ------------------------------------------------------- 利用者の参加(両シナリオ共通)

When('新しい利用者をそのプロジェクトへauthorとして参加させる', async ({ ctx, request }) => {
  const suffix = uniqueSuffix();
  const email = `e2e-1176-author-${suffix}@example.com`;
  const password = `E2e1176Author!${suffix}`;

  const headers = await adminHeaders(request);
  const created = await request.post('/api/users', {
    headers,
    data: { email, password, role: 'user' },
  });
  expect(
    created.ok(),
    `検証用利用者の登録に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const userId = ((await created.json()) as { id: number }).id;

  provisionLoginableKeycloakCredential(email, password);

  const projectId = ctx.authorProjectId as number;
  const added = await request.post(`/api/projects/${projectId}/users`, {
    headers,
    data: { userId, wpRole: 'author' },
  });
  expect(
    added.ok(),
    `プロジェクトへの参加に失敗しました (status=${added.status()}): ${await added.text()}`
  ).toBe(true);

  ctx.authorMemberId = userId;
  ctx.authorMemberEmail = email;
  ctx.authorMemberPassword = password;
});

// ------------------------------------------------------- シナリオ1: 著者マッピングの正しさ(AC1)

When('その利用者として記事を公開する', async ({ ctx, request }) => {
  const email = ctx.authorMemberEmail as string;
  const password = ctx.authorMemberPassword as string;
  const memberToken = await fetchAccessToken(request, email, password);

  const unique = uniqueSuffix();
  const title = `E2E-1176-Author-${unique}`;
  const slug = `e2e-1176-author-${unique}`;

  const response = await request.post('/api/posts/publish', {
    headers: { Authorization: `Bearer ${memberToken}` },
    multipart: {
      site: ctx.authorSiteKey as string,
      title,
      slug,
      status: 'publish',
      markdown: `# ${title}\n\nissue #1176のE2Eが投稿した検証記事です。\n`,
    },
    timeout: 120_000,
  });
  expect(
    response.ok(),
    `記事公開に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const body = (await response.json()) as PostPublishResponse;
  expect(body.wpPostId, '公開応答にwpPostIdが含まれません').toBeTruthy();

  ctx.authorPublishedPostId = body.wpPostId;
});

Then('WordPress側のその投稿の投稿者は、参加させた利用者に対応するWordPressユーザーと一致する', async ({ ctx }) => {
  const slug = ctx.authorSiteSlug as string;
  const postId = ctx.authorPublishedPostId as string;
  const email = ctx.authorMemberEmail as string;

  const wpUsers = findWpUserByEmail(slug, email);
  expect(
    wpUsers,
    `WordPress側に ${email} に対応するユーザーが見つかりません: ${JSON.stringify(wpUsers)}`
  ).toHaveLength(1);

  const actualAuthorId = postAuthor(slug, postId);
  expect(
    actualAuthorId,
    `投稿(id=${postId})の投稿者(${actualAuthorId})が、参加させた利用者のWordPressユーザー(${wpUsers[0].ID})と一致しません`
  ).toBe(wpUsers[0].ID);
});

// ------------------------------------------------------- シナリオ2: 著者の自動プロビジョニング(AC2)

Then('WordPress側にその利用者のメールアドレスに対応する新規ユーザーが作成されている', async ({ ctx }) => {
  const slug = ctx.authorSiteSlug as string;
  const email = ctx.authorMemberEmail as string;

  const wpUsers = findWpUserByEmail(slug, email);
  expect(
    wpUsers,
    `WordPress側に ${email} に対応する自動プロビジョニング済みユーザーが見つかりません: ${JSON.stringify(wpUsers)}`
  ).toHaveLength(1);
});

// ------------------------------------------------------- 後片付け

After({ tags: '@publishing' }, async ({ ctx, request }) => {
  const projectId = ctx.authorProjectId as number | undefined;
  if (projectId !== undefined) {
    const token = await adminToken(request);
    await deleteFixtureProject(request, token, projectId);
  }

  const memberId = ctx.authorMemberId as number | undefined;
  if (memberId !== undefined) {
    const headers = await adminHeaders(request);
    await request.delete(`/api/users/${memberId}`, { headers });
  }

  const slug = ctx.authorSiteSlug as string | undefined;
  const postId = ctx.authorPublishedPostId as string | undefined;
  if (slug && postId) {
    try {
      wpCli(slug, `post delete ${postId} --force`);
    } catch {
      // 既に削除済み等は後片付けの失敗としては扱わない。
    }
  }

  const email = ctx.authorMemberEmail as string | undefined;
  if (slug && email) {
    try {
      const wpUsers = findWpUserByEmail(slug, email);
      for (const wpUser of wpUsers) {
        wpCli(slug, `user delete ${wpUser.ID} --yes`);
      }
    } catch {
      // 投稿削除前にユーザーへ紐づく投稿が他に残っている等は後片付けの失敗としては扱わない。
    }
  }
});
