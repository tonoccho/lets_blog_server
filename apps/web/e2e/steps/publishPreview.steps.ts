import { execFileSync } from 'node:child_process';
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
 * 記事プレビューと一時投稿の後始末(issue #1175 / AT-6-5)のステップ定義。
 *
 * 兄弟issue(#932系列の子issue群)とステップ定義ファイルを共有しない方針
 * (`publishTaxonomy.steps.ts`と同様。相乗りしない)のため、必要なヘルパーはこのファイル内に
 * 閉じて持つ。
 *
 * ## 専用サイトを冪等に用意する理由
 *
 * プレビューの`/skeleton`は、サーバー側でwp-cliを実行できる認証情報(managed WordPressの
 * agent transport)の非本番サイトに対しては、プレビュー対象記事を実際に非公開(private)投稿
 * としてWordPressへ作成する経路を使う(`ArticlePreviewService#renderSkeleton`)。この経路が
 * 「公開はされていない」「後始末で本当に消える」を満たすかは、実際のWordPressの状態を
 * wp-cliで見ないと確かめられない。#1167のプロビジョニング済みサイト共有フィクスチャ
 * (`site-provisioning.steps.ts`)はサイトの識別子だけを永続化しWordPress管理者の認証情報は
 * 残さないため、wp-cliで直接アクセスする必要がある本ファイルには使えない。そこで
 * `publishTaxonomy.steps.ts`(issue #1174)と同じ「固定siteKeyで冪等に用意し、実行をまたいで
 * 再利用する」パターンを踏襲する。
 */

/** リポジトリルート(apps/web/e2e/steps から4階層上)。 */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

/** プレビュー検証用サイト。冪等に用意し、実行をまたいで再利用する。 */
const PREVIEW_SITE_KEY = 'at65previewprobe';
const PREVIEW_SITE_ADMIN_USER = 'at65previewadmin';

/** WordPress自動構築の待ち上限。分単位でかかりうる。 */
const PROVISION_TIMEOUT_MS = 600_000;

interface SiteFixture {
  id: number;
  siteKey: string;
}

interface ThemeSkeletonResponse {
  html: string | null;
  available: boolean;
  reason: string | null;
  eyecatchSpliced: boolean;
  css: string;
  previewPostId: string | null;
  warning: string | null;
}

interface ThemeCssResponse {
  css: string;
  available: boolean;
  reason: string | null;
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

/** 投稿の現在のステータス(publish/private/trash等)。投稿が存在しなければnull。 */
function postStatus(slug: string, postId: string): string | null {
  try {
    return wpCli(slug, `post get ${postId} --field=post_status`);
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

async function ensureManagedSite(request: APIRequestContext): Promise<SiteFixture> {
  const headers = await adminHeaders(request);
  const list = await request.get('/api/sites', { headers });
  expect(
    list.ok(),
    `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`
  ).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find((site) => site.siteKey === PREVIEW_SITE_KEY);
  if (existing) {
    return existing;
  }
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: 'AT6-5 preview probe site',
      siteKey: PREVIEW_SITE_KEY,
      title: 'AT6-5 Preview Probe',
      adminUser: PREVIEW_SITE_ADMIN_USER,
      adminEmail: 'at65-preview-probe@letsblog.local',
      adminPassword: 'At65Preview#Passw0rd1',
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
      name: 'AT6-5 preview probe site',
      siteKey: PREVIEW_SITE_KEY,
      adminUser: PREVIEW_SITE_ADMIN_USER,
    },
  });
  expect(
    adopted.ok(),
    `既存WordPressの取り込みに失敗しました (status=${adopted.status()}): ${await adopted.text()}`
  ).toBe(true);
  return (await adopted.json()) as SiteFixture;
}

async function renderSkeleton(
  request: APIRequestContext,
  projectId: number,
  siteId: number,
  title: string,
  contentHtml: string
): Promise<ThemeSkeletonResponse> {
  const headers = await adminHeaders(request);
  const response = await request.post(`/api/projects/${projectId}/preview/skeleton`, {
    headers,
    data: { title, contentHtml, siteId },
  });
  expect(
    response.ok(),
    `プレビューの骨組み差し込みに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as ThemeSkeletonResponse;
}

async function fetchThemeCss(
  request: APIRequestContext,
  projectId: number,
  siteId: number
): Promise<ThemeCssResponse> {
  const headers = await adminHeaders(request);
  const response = await request.get(`/api/projects/${projectId}/preview/theme-css`, {
    headers,
    params: { siteId },
  });
  expect(
    response.ok(),
    `プレビュー用テーマCSSの取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as ThemeCssResponse;
}

async function deletePreviewPost(
  request: APIRequestContext,
  projectId: number,
  siteId: number,
  postId: string
): Promise<void> {
  const headers = await adminHeaders(request);
  const response = await request.delete(`/api/projects/${projectId}/preview/preview-post`, {
    headers,
    params: { siteId, postId },
  });
  expect(
    response.ok(),
    `プレビュー用一時投稿の削除に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

// ------------------------------------------------------- 背景

Given('プレビュー検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている', async ({ ctx, request }) => {
  const site = await ensureManagedSite(request);
  const token = await adminToken(request);
  const project = await createFixtureProject(request, token, 'at65-preview');
  const headers = await adminHeaders(request);
  const bound = await request.post(`/api/projects/${project.id}/environments`, {
    headers,
    data: { environment: 'test', siteId: site.id },
  });
  expect(
    bound.ok(),
    `テスト環境へのサイト紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`
  ).toBe(true);

  ctx.previewSiteId = site.id;
  ctx.previewSiteSlug = wpSlug(site.siteKey);
  ctx.previewProjectId = project.id;
});

// ------------------------------------------------------- シナリオ1: 公開せずに実テーマの見た目(AC1)

When('記事をプレビューする', async ({ ctx, request }) => {
  const unique = uniqueSuffix();
  const title = `E2E-1175-Preview-${unique}`;
  const contentHtml = `<p>E2E-1175 preview body ${unique}</p>`;
  const result = await renderSkeleton(
    request,
    ctx.previewProjectId as number,
    ctx.previewSiteId as number,
    title,
    contentHtml
  );
  ctx.previewTitle = title;
  ctx.previewSkeletonResult = result;
  ctx.previewPostId = result.previewPostId;
  ctx.previewThemeCssResult = await fetchThemeCss(
    request,
    ctx.previewProjectId as number,
    ctx.previewSiteId as number
  );
});

Then('プレビューに実テーマのCSSを当てた見た目が返る', async ({ ctx }) => {
  const result = ctx.previewSkeletonResult as ThemeSkeletonResponse;
  expect(result.available, `プレビューの取得に失敗しました: ${result.reason}`).toBe(true);
  expect(result.html, 'プレビューHTMLが空です').toBeTruthy();
  expect((result.html as string).includes(ctx.previewTitle as string), 'プレビューHTMLに記事タイトルが含まれません').toBe(true);
  expect(result.css.length > 0, '実テーマのCSSが取得できていません').toBe(true);
  expect(result.previewPostId, 'プレビュー用一時投稿のIDが返っていません').toBeTruthy();
});

Then('プレビュー用テーマCSS取得APIも公開先サイトの実CSSを返す', async ({ ctx }) => {
  const result = ctx.previewThemeCssResult as ThemeCssResponse;
  expect(result.available, `テーマCSSの取得に失敗しました: ${result.reason}`).toBe(true);
  expect(result.css.length > 0, 'テーマCSSが空です').toBe(true);
});

Then('WordPress側にその記事は非公開の一時投稿としてのみ存在し公開はされていない', async ({ ctx }) => {
  const slug = ctx.previewSiteSlug as string;
  const postId = ctx.previewPostId as string;
  const status = postStatus(slug, postId);
  expect(status, `WordPress側にプレビュー用の投稿(id=${postId})が見つかりません`).toBe('private');
});

// ------------------------------------------------------- シナリオ2: 後始末(AC2)

When('記事をプレビューしてからプレビューを終了する', async ({ ctx, request }) => {
  const unique = uniqueSuffix();
  const title = `E2E-1175-Cleanup-${unique}`;
  const contentHtml = `<p>E2E-1175 cleanup body ${unique}</p>`;
  const result = await renderSkeleton(
    request,
    ctx.previewProjectId as number,
    ctx.previewSiteId as number,
    title,
    contentHtml
  );
  expect(result.available, `プレビューの取得に失敗しました: ${result.reason}`).toBe(true);
  expect(result.previewPostId, 'プレビュー用一時投稿のIDが返っていません').toBeTruthy();
  ctx.previewPostId = result.previewPostId;

  await deletePreviewPost(
    request,
    ctx.previewProjectId as number,
    ctx.previewSiteId as number,
    result.previewPostId as string
  );
});

Then('WordPress側にそのプレビュー用の一時投稿が残っていない', async ({ ctx }) => {
  const slug = ctx.previewSiteSlug as string;
  const postId = ctx.previewPostId as string;
  const status = postStatus(slug, postId);
  expect(
    status === null || status === 'trash',
    `プレビュー用の一時投稿(id=${postId})がWordPress側に残っています(status=${status})`
  ).toBe(true);
});

// ------------------------------------------------------- 後片付け

After({ tags: '@publishing' }, async ({ ctx, request }) => {
  const projectId = ctx.previewProjectId as number | undefined;
  if (projectId === undefined) {
    return;
  }
  const token = await adminToken(request);
  await deleteFixtureProject(request, token, projectId);

  const slug = ctx.previewSiteSlug as string | undefined;
  const postId = ctx.previewPostId as string | null | undefined;
  if (slug && postId) {
    try {
      wpCli(slug, `post delete ${postId} --force`);
    } catch {
      // 既に削除済み等は後片付けの失敗としては扱わない。
    }
  }
});
