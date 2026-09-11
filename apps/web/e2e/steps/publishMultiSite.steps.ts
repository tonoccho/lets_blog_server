import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 複数サイトへの同時公開と一部失敗時の挙動(issue #1173 / AT-6-3)のステップ定義。
 *
 * 兄弟issue(#932系列の子issue群)とステップ定義ファイルを共有しない方針
 * (`publishLifecycle.steps.ts`・`publishStatus.steps.ts`・`publishTaxonomy.steps.ts`・
 * `publishAuthor.steps.ts`・`publishPreview.steps.ts`と同様。相乗りしない)のため、
 * 必要なヘルパーはこのファイル内に閉じて持つ。
 *
 * ## 専用サイトを冪等に用意する理由
 *
 * 「両方のサイトに記事が作成される」の正しさは実際のWordPress側の投稿の状態(wp-cli)を
 * 見ないと確かめられない。#1167のプロビジョニング済みサイト共有フィクスチャ
 * (`site-provisioning.steps.ts`)はサイトの識別子だけを永続化しWordPress管理者の認証情報は
 * 残さないため、wp-cliで直接アクセスする必要がある本ファイルには使えない。そこで
 * `publishLifecycle.steps.ts`(#1171)と同じ「固定siteKeyで冪等に用意し、実行をまたいで
 * 再利用する」パターンを踏襲する。
 *
 * ## 到達不能サイトはプロジェクトに紐付けない
 *
 * `POST /api/posts/publish` は `siteKey` を直接受け取り(`PostPublishService#publish`)、
 * 対象サイトがどのプロジェクトの環境にも紐付いていない場合は
 * `AdminAuthorizationService#requireProjectMemberOrAdminForSite` がグローバル管理者を
 * そのまま許可する。この受け入れテストはE2E管理者アカウントで実行するため、プロジェクトへの
 * 紐付けを一切行わずにサイトA・サイトB・到達不能サイトを直接公開先として使う。
 */

/** リポジトリルート(apps/web/e2e/steps から4階層上)。 */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

/** 公開検証用サイト。冪等に用意し、実行をまたいで再利用する(`publishLifecycle.steps.ts`と同じ方針)。 */
const SITE_A_KEY = 'at63publishsitea';
const SITE_A_ADMIN_USER = 'at63publishsitea';
const SITE_B_KEY = 'at63publishsiteb';
const SITE_B_ADMIN_USER = 'at63publishsiteb';

/** WordPress自動構築の待ち上限。分単位でかかりうる。 */
const PROVISION_TIMEOUT_MS = 600_000;

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

/** 投稿の指定フィールドをwp-cliで取得する。投稿が存在しなければnull。 */
function postField(slug: string, postId: string, field: string): string | null {
  try {
    return wpCli(slug, `post get ${postId} --field=${field}`);
  } catch {
    // `wp post get`は対象が存在しない場合に非ゼロ終了する。
    return null;
  }
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

/** 固定siteKeyのマネージドWordPressサイトを冪等に用意する(`publishLifecycle.steps.ts`のensureManagedSiteと同じ方針)。 */
async function ensureManagedSite(
  request: APIRequestContext,
  siteKey: string,
  adminUser: string,
  label: string
): Promise<SiteFixture> {
  const headers = await adminHeaders(request);
  const list = await request.get('/api/sites', { headers });
  expect(
    list.ok(),
    `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`
  ).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find((site) => site.siteKey === siteKey);
  if (existing) {
    return existing;
  }
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `AT6-3 ${label}`,
      siteKey,
      title: `AT6-3 ${label}`,
      adminUser,
      adminEmail: `${siteKey}@letsblog.local`,
      adminPassword: 'At63Publish#Passw0rd1',
    },
    timeout: PROVISION_TIMEOUT_MS,
  });
  if (created.status() === 409) {
    // provision-agent側には既に実体があるがDBには未登録(中断した前回実行の取り残し)。
    // site-adoption.steps.tsと同じ「取り込み」で救う(publishLifecycle.steps.tsと同じ方針)。
    const adopted = await request.post('/api/sites/managed-wordpress/adopt', {
      headers,
      data: { name: `AT6-3 ${label}`, siteKey, adminUser },
    });
    expect(
      adopted.ok(),
      `既存WordPressの取り込みに失敗しました (status=${adopted.status()}): ${await adopted.text()}`
    ).toBe(true);
    return (await adopted.json()) as SiteFixture;
  }
  expect(
    created.ok(),
    `マネージドWordPressの構築に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  return (await created.json()) as SiteFixture;
}

/** 到達不能なSSHホストを指す使い捨ての既存WordPressサイトを登録する(`siteRegistration.steps.ts`と同じパターン)。 */
async function registerUnreachableSite(request: APIRequestContext): Promise<SiteFixture> {
  const headers = await adminHeaders(request);
  const unique = uniqueSuffix();
  const siteKey = `e2e-1173-unreachable-${unique}`;
  const response = await request.post('/api/sites', {
    headers,
    data: {
      name: `E2E 1173 unreachable ${unique}`,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'SSH',
        baseUrl: 'http://wrong.invalid',
        sshHost: 'wrong.invalid',
        sshUser: 'nouser',
        wpPath: '/nowhere',
        sshPrivateKeyPem:
          '-----BEGIN OPENSSH PRIVATE KEY-----\ne2e-1173-fixture-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----',
      },
    },
  });
  expect(
    response.ok(),
    `到達不能サイトの登録に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const body = (await response.json()) as { id: number };
  return { id: body.id, siteKey };
}

async function publish(
  request: APIRequestContext,
  token: string,
  siteKey: string,
  title: string,
  slug: string,
  markdown: string
): Promise<{ ok: boolean; status: number; body: string; parsed: PostPublishResponse | null }> {
  const form = new FormData();
  form.append('site', siteKey);
  form.append('title', title);
  form.append('slug', slug);
  form.append('status', 'publish');
  form.append('markdown', markdown);

  const response = await request.post('/api/posts/publish', {
    headers: { Authorization: `Bearer ${token}` },
    multipart: form,
    timeout: 120_000,
  });
  const bodyText = await response.text();
  let parsed: PostPublishResponse | null = null;
  if (response.ok()) {
    parsed = JSON.parse(bodyText) as PostPublishResponse;
  }
  return { ok: response.ok(), status: response.status(), body: bodyText, parsed };
}

// ------------------------------------------------------- 背景

Given('複数サイト公開検証用のWordPressサイトAとサイトBがある', async ({ ctx, request }) => {
  const [siteA, siteB] = await Promise.all([
    ensureManagedSite(request, SITE_A_KEY, SITE_A_ADMIN_USER, 'multi-site publish site A'),
    ensureManagedSite(request, SITE_B_KEY, SITE_B_ADMIN_USER, 'multi-site publish site B'),
  ]);
  ctx.siteAId = siteA.id;
  ctx.siteASlug = wpSlug(siteA.siteKey);
  ctx.siteBId = siteB.id;
  ctx.siteBSlug = wpSlug(siteB.siteKey);
  ctx.multiSiteCleanupPostIds = [] as { slug: string; postId: string }[];
});

// ------------------------------------------------------- シナリオ1: 2サイトへの同時公開(親シナリオ8)

When('同じ記事をサイトAとサイトBへ公開する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const unique = uniqueSuffix();
  const title = `E2E-1173-Multi-${unique}`;
  const slug = `e2e-1173-multi-${unique}`;
  const markdown = `# ${title}\n\nissue #1173のE2Eが複数サイトへ同時公開した検証記事です。\n`;

  const [resultA, resultB] = await Promise.all([
    publish(request, token, SITE_A_KEY, title, slug, markdown),
    publish(request, token, SITE_B_KEY, title, slug, markdown),
  ]);

  expect(resultA.ok, `サイトAへの公開に失敗しました (status=${resultA.status}): ${resultA.body}`).toBe(true);
  expect(resultB.ok, `サイトBへの公開に失敗しました (status=${resultB.status}): ${resultB.body}`).toBe(true);

  const cleanup = ctx.multiSiteCleanupPostIds as { slug: string; postId: string }[];
  cleanup.push({ slug: ctx.siteASlug as string, postId: (resultA.parsed as PostPublishResponse).wpPostId });
  cleanup.push({ slug: ctx.siteBSlug as string, postId: (resultB.parsed as PostPublishResponse).wpPostId });

  ctx.siteAPostId = (resultA.parsed as PostPublishResponse).wpPostId;
  ctx.siteBPostId = (resultB.parsed as PostPublishResponse).wpPostId;
});

Then('サイトAにその記事が公開状態で作成されている', async ({ ctx }) => {
  const status = postField(ctx.siteASlug as string, ctx.siteAPostId as string, 'post_status');
  expect(status, `サイトA側に投稿(id=${ctx.siteAPostId})が見つかりません`).toBe('publish');
});

Then('サイトBにその記事が公開状態で作成されている', async ({ ctx }) => {
  const status = postField(ctx.siteBSlug as string, ctx.siteBPostId as string, 'post_status');
  expect(status, `サイトB側に投稿(id=${ctx.siteBPostId})が見つかりません`).toBe('publish');
});

// ------------------------------------------------- シナリオ2: 一部失敗時の挙動(親シナリオ9)

Given('資格情報が誤っている到達不能なサイトを登録しておく', async ({ ctx, request }) => {
  const site = await registerUnreachableSite(request);
  ctx.unreachableSiteId = site.id;
  ctx.unreachableSiteKey = site.siteKey;
});

When('同じ記事をサイトAと到達不能なサイトへ公開する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const unique = uniqueSuffix();
  const title = `E2E-1173-Partial-${unique}`;
  const slug = `e2e-1173-partial-${unique}`;
  const markdown = `# ${title}\n\nissue #1173のE2Eが一部失敗を検証した記事です。\n`;

  const [resultA, resultUnreachable] = await Promise.all([
    publish(request, token, SITE_A_KEY, title, slug, markdown),
    publish(request, token, ctx.unreachableSiteKey as string, title, slug, markdown),
  ]);

  expect(resultA.ok, `サイトAへの公開に失敗しました (status=${resultA.status}): ${resultA.body}`).toBe(true);
  const cleanup = ctx.multiSiteCleanupPostIds as { slug: string; postId: string }[];
  cleanup.push({ slug: ctx.siteASlug as string, postId: (resultA.parsed as PostPublishResponse).wpPostId });
  ctx.siteAPostId = (resultA.parsed as PostPublishResponse).wpPostId;

  ctx.unreachablePublishResult = resultUnreachable;
});

Then('到達不能なサイトへの公開はエラー応答で失敗する', async ({ ctx }) => {
  const result = ctx.unreachablePublishResult as { ok: boolean; status: number; body: string };
  expect(
    result.ok,
    `到達不能なサイトへの公開が成功として応答しました(失敗として利用者に示されるべきです): ${result.body}`
  ).toBe(false);
  expect(result.status, `到達不能なサイトへの公開は502(疎通失敗)として応答するべきです: ${result.body}`).toBe(502);
});

// ------------------------------------------------------- 後片付け

After({ tags: '@publishing' }, async ({ ctx, request }) => {
  const cleanup = (ctx.multiSiteCleanupPostIds as { slug: string; postId: string }[] | undefined) ?? [];
  for (const { slug, postId } of cleanup) {
    try {
      wpCli(slug, `post delete ${postId} --force`);
    } catch {
      // 既に削除済み等は後片付けの失敗としては扱わない。
    }
  }

  const unreachableSiteId = ctx.unreachableSiteId as number | undefined;
  if (unreachableSiteId !== undefined) {
    const headers = await adminHeaders(request);
    await request.delete(`/api/sites/${unreachableSiteId}`, { headers });
  }
});
