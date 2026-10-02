import { execFileSync } from 'node:child_process';
import type { APIRequestContext } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { STUB_URLS } from '../support/stubs';

/**
 * レビュー API(PR の記事をテスト環境へ投稿する)の受け入れシナリオを支えるステップ定義(issue #1341)。
 *
 * プロジェクトのフィクスチャは記事プランの `articlePlan.steps.ts`(`ctx.plan`)と同じ形で作り、
 * `@plan` の After が後片付けする(プロジェクトと、テスト環境として作ったサイトを消す)。
 * 兄弟 issue のステップ定義とは共有せず、必要なヘルパーはこのファイルに閉じて持つ
 * (`articleReviewSubmission.steps.ts` と同じ方針)。
 *
 * - GitHub スタブへ PR・ブランチ・ファイルを一意な名前で作り、提出 API で `article_reviews` の行を作る。
 * - 投稿の実体は wp-cli(`lbs-wordpress`)で、レビューの記録は `lbs_publishing.article_reviews` を
 *   `lbs-mysql` から直接読んで確かめる(この API の応答だけでは「記録された」ことを確かめられない)。
 */

const REPO = '/repos/e2e-stub/acceptance';
const STUB_REPOSITORY = 'e2e-stub/acceptance';
const STUB_TOKEN = 'e2e-stub-token';
const WORDPRESS_CONTAINER = 'lbs-wordpress';
const MYSQL_CONTAINER = 'lbs-mysql';
/** 1x1 の透明 PNG。画像のリサイズ・アップロードを実際に通すため、本物の PNG を使う。 */
const TINY_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';

interface PlanFixture {
  projectId: number;
  siteId?: number;
}

interface ReviewFixture {
  prNumber: number;
  head: string;
  slug: string;
}

interface ApiResponse {
  status: number;
  text: string;
  json: any;
}

function unique(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function stub(method: string, pathname: string, body?: unknown): Promise<{ status: number; json: any }> {
  const res = await fetch(`${STUB_URLS.github}${pathname}`, {
    method,
    headers: {
      Authorization: `Bearer ${STUB_TOKEN}`,
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  return { status: res.status, json: text ? JSON.parse(text) : null };
}

async function putFileBase64(head: string, path: string, base64: string): Promise<void> {
  const res = await stub('PUT', `${REPO}/contents/${path}`, {
    message: `E2E: ${path}`,
    branch: head,
    content: base64,
  });
  expect(res.status, `スタブへのファイル配置に失敗(${path}): ${JSON.stringify(res.json)}`).toBeLessThan(300);
}

async function putFile(head: string, path: string, content: string): Promise<void> {
  await putFileBase64(head, path, Buffer.from(content, 'utf8').toString('base64'));
}

function articleMarkdown(slug: string, title: string, body: string): string {
  return [
    '---',
    `title: ${title}`,
    `slug: ${slug}`,
    'status: draft',
    'featured_image: assets/cover.png',
    '---',
    body,
    '',
  ].join('\n');
}

function plan(ctx: Record<string, unknown>): PlanFixture {
  const fixture = ctx.plan as PlanFixture | undefined;
  if (!fixture) {
    throw new Error('先にプロジェクトを用意するステップを実行すること');
  }
  return fixture;
}

function review(ctx: Record<string, unknown>): ReviewFixture {
  const fixture = ctx.reviewFixture as ReviewFixture | undefined;
  if (!fixture) {
    throw new Error('先に提出済みのPRを用意するステップを実行すること');
  }
  return fixture;
}

function last(ctx: Record<string, unknown>): ApiResponse {
  const response = ctx.reviewResponse as ApiResponse | undefined;
  if (!response) {
    throw new Error('先にレビューを開始するステップを実行すること');
  }
  return response;
}

function siteKeyOf(ctx: Record<string, unknown>): string {
  const key = ctx.reviewSiteKey as string | undefined;
  if (!key) {
    throw new Error('テスト環境のサイトが用意されていない');
  }
  return key;
}

async function call(response: Awaited<ReturnType<APIRequestContext['post']>>): Promise<ApiResponse> {
  const text = await response.text();
  let json: any = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  return { status: response.status(), text, json };
}

function wpCli(siteKey: string, args: string[]): string {
  return execFileSync(
    'docker',
    ['exec', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`, ...args],
    { encoding: 'utf8', timeout: 120_000 }
  ).trim();
}

function mysqlRootPassword(): string {
  return execFileSync('docker', ['exec', MYSQL_CONTAINER, 'printenv', 'MYSQL_ROOT_PASSWORD'], {
    encoding: 'utf8',
    timeout: 30_000,
  }).trim();
}

/** `lbs_publishing.article_reviews` の1行を [state, test_post_url, reviewed_by_user_id] で返す。 */
function readReviewRow(projectId: number, prNumber: number): string[] {
  const out = execFileSync(
    'docker',
    [
      'exec', '-e', `MYSQL_PWD=${mysqlRootPassword()}`, MYSQL_CONTAINER, 'mysql', '-uroot', '-N', '-B', '-e',
      'SELECT state, IFNULL(test_post_url, ""), IFNULL(reviewed_by_user_id, "") FROM lbs_publishing.article_reviews ' +
        `WHERE project_id = ${projectId} AND github_pr_number = ${prNumber}`,
    ],
    { encoding: 'utf8', timeout: 60_000 }
  ).replace(/[\r\n]+$/, ''); // 末尾の空フィールド(タブ)を落とさないよう trim() は使わない
  expect(out, `article_reviews に行が無い(project=${projectId}, pr=${prNumber})`).not.toBe('');
  return out.split('\t');
}

async function adminUserId(request: APIRequestContext): Promise<number> {
  const token = await adminToken(request);
  const response = await request.get('/api/identity/me', { headers: { Authorization: `Bearer ${token}` } });
  expect(response.ok(), `本人情報の取得に失敗した (status=${response.status()})`).toBe(true);
  return ((await response.json()) as { id: number }).id;
}

async function createProject(request: APIRequestContext, token: string): Promise<number> {
  const suffix = unique();
  const response = await request.post('/api/projects', {
    headers: { Authorization: `Bearer ${token}` },
    data: { name: `E2E 1341 ${suffix}`, slug: `e2e-1341-${suffix}` },
  });
  expect(
    response.ok(),
    `レビュー用プロジェクトの作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return ((await response.json()) as { id: number }).id;
}

async function linkGithub(request: APIRequestContext, token: string, projectId: number): Promise<void> {
  const headers = { Authorization: `Bearer ${token}` };
  const repo = await request.put(`/api/projects/${projectId}/github-repository`, {
    headers,
    data: { githubRepository: STUB_REPOSITORY },
  });
  expect(repo.ok(), `GitHubリポジトリの紐付けに失敗 (status=${repo.status()}): ${await repo.text()}`).toBe(true);
  const key = await request.put(`/api/projects/${projectId}/api-keys/github-token`, {
    headers,
    data: { githubToken: STUB_TOKEN },
  });
  expect(key.ok(), `GitHubトークンの設定に失敗 (status=${key.status()}): ${await key.text()}`).toBe(true);
}

/** テスト環境用のマネージドWordPressを作り、プロジェクトのテスト環境へ紐づける。 */
async function createTestEnvironmentSite(
  request: APIRequestContext, token: string, projectId: number
): Promise<{ siteId: number; siteKey: string }> {
  const siteKey = `at1341${unique()}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const created = await request.post('/api/sites/managed-wordpress', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name: `E2E 1341 ${siteKey}`,
      siteKey,
      title: 'E2E 1341 test environment',
      adminUser: 'at1341admin',
      adminEmail: 'at1341@letsblog.local',
      adminPassword: 'At1341Fixture!Pass123',
      locale: 'ja',
    },
    timeout: 120_000,
  });
  expect(
    created.ok(),
    `テスト環境サイトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const siteId = ((await created.json()) as { id: number }).id;
  const bound = await request.post(`/api/projects/${projectId}/environments`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { environment: 'test', siteId },
  });
  expect(
    bound.ok(),
    `テスト環境への紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`
  ).toBe(true);
  return { siteId, siteKey };
}

/** 記事を含むPRをスタブに作り、提出APIで `article_reviews` の行を作る。 */
async function prepareSubmittedPr(
  ctx: Record<string, unknown>, request: APIRequestContext, slug: string, title: string, body: string
): Promise<void> {
  const head = `article/e2e-review-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const created = await stub('POST', `${REPO}/pulls`, {
    title: `E2Eスタブ: ${head}`, head, base: 'main', body: 'レビューAPIの受け入れテスト',
  });
  expect(created.status, `スタブへのPR作成に失敗: ${JSON.stringify(created.json)}`).toBe(201);
  await putFile(head, `articles/${slug}/article.md`, articleMarkdown(slug, title, body));
  await putFileBase64(head, `articles/${slug}/assets/cover.png`, TINY_PNG_BASE64);

  const token = await adminToken(request);
  const submitted = await call(
    await request.post(`/api/projects/${plan(ctx).projectId}/article-review/submissions`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { headBranch: head, githubIssueNumber: 101, articleSlug: slug },
    })
  );
  expect(submitted.status, `提出に失敗した: ${submitted.text}`).toBe(200);
  expect(submitted.json.prNumber, '提出がスタブ上のPRを引き当てていない').toBe(created.json.number);
  ctx.reviewFixture = { prNumber: created.json.number, head, slug } satisfies ReviewFixture;
}

async function startReview(ctx: Record<string, unknown>, request: APIRequestContext): Promise<ApiResponse> {
  const token = await adminToken(request);
  return call(
    await request.post(
      `/api/projects/${plan(ctx).projectId}/article-review/pull-requests/${review(ctx).prNumber}/review`,
      { headers: { Authorization: `Bearer ${token}` }, timeout: 180_000 }
    )
  );
}

function postIdsBySlug(siteKey: string, slug: string): string[] {
  const out = wpCli(siteKey, ['post', 'list', `--name=${slug}`, '--post_type=post', '--post_status=any', '--format=ids']);
  return out === '' ? [] : out.split(/\s+/);
}

// ------------------------------------------------------------ 前提

Given('GitHub連携とテスト環境のサイトが設定されたプロジェクトが用意されている', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = await createProject(request, token);
  ctx.plan = { projectId } satisfies PlanFixture;
  await linkGithub(request, token, projectId);
  const site = await createTestEnvironmentSite(request, token, projectId);
  ctx.plan = { projectId, siteId: site.siteId } satisfies PlanFixture;
  ctx.reviewSiteKey = site.siteKey;
});

Given(
  /^記事「(.+)」のtitleが「(.+)」である提出済みのPRがスタブに用意されている$/,
  async ({ ctx, request }, slug: string, title: string) => {
    await prepareSubmittedPr(ctx, request, slug, title, '# 見出し\n\nレビューAPIの本文です');
  }
);

Given(
  /^本文に不正なrechartsタグを含む記事「(.+)」の提出済みのPRがスタブに用意されている$/,
  async ({ ctx, request }, slug: string) => {
    await prepareSubmittedPr(
      ctx, request, slug, '壊れた記事', '[recharts]\n| a | b |\n|---|---|\n| x | 1 |\n[/recharts]'
    );
  }
);

Given('管理者がそのPRのレビューを開始済みである', async ({ ctx, request }) => {
  const first = await startReview(ctx, request);
  expect(first.status, `1回目のレビューに失敗した: ${first.text}`).toBe(200);
  ctx.firstReviewResponse = first;
});

// ------------------------------------------------------------ 操作

When('管理者がそのPRのレビューを開始する', async ({ ctx, request }) => {
  ctx.reviewResponse = await startReview(ctx, request);
});

When(/^そのPRのheadブランチにtitleが「(.+)」の新しいコミットを積む$/, async ({ ctx }, title: string) => {
  const fixture = review(ctx);
  await putFile(
    fixture.head,
    `articles/${fixture.slug}/article.md`,
    articleMarkdown(fixture.slug, title, '# 見出し\n\nレビューAPIの本文です')
  );
});

// ------------------------------------------------------------ 検証

Then('レビューの開始は成功する', async ({ ctx }) => {
  expect(last(ctx).status, `レビューの開始に失敗した: ${last(ctx).text}`).toBe(200);
});

Then(/^レビューの開始は「(\d+)」で失敗し、エラーに「(.+)」が含まれる$/, async ({ ctx }, status: string, fragment: string) => {
  expect(last(ctx).status, `期待した失敗にならなかった: ${last(ctx).text}`).toBe(Number(status));
  expect(last(ctx).text).toContain(fragment);
});

Then(/^レビューの開始は「(\d+)」で失敗する$/, async ({ ctx }, status: string) => {
  expect(last(ctx).status, `期待した失敗にならなかった: ${last(ctx).text}`).toBe(Number(status));
});

Then(
  /^テスト環境のサイトにスラッグ「(.+)」の記事が公開状態で1件あり、titleは「(.+)」である$/,
  async ({ ctx }, slug: string, title: string) => {
    const ids = postIdsBySlug(siteKeyOf(ctx), slug);
    expect(ids, `テスト環境のサイトのスラッグ「${slug}」の記事数`).toHaveLength(1);
    expect(wpCli(siteKeyOf(ctx), ['post', 'get', ids[0], '--field=post_status'])).toBe('publish');
    expect(wpCli(siteKeyOf(ctx), ['post', 'get', ids[0], '--field=post_title'])).toBe(title);
    expect(wpCli(siteKeyOf(ctx), ['post', 'get', ids[0], '--field=post_content'])).toContain('レビューAPIの本文です');
    ctx.reviewPostId = ids[0];
  }
);

Then('テスト環境のサイトにスラッグ「review-broken」の記事は存在しない', async ({ ctx }) => {
  expect(postIdsBySlug(siteKeyOf(ctx), 'review-broken')).toHaveLength(0);
});

Then('レビューの応答のURLはテスト環境のその記事のURLと一致する', async ({ ctx }) => {
  const url = last(ctx).json?.testPostUrl as string | undefined;
  expect(url, `応答にURLが無い: ${last(ctx).text}`).toBeTruthy();
  expect(url).toBe(wpCli(siteKeyOf(ctx), ['post', 'get', ctx.reviewPostId as string, '--field=url']));
});

Then('レビューの応答の投稿IDは1回目のレビューと同じである', async ({ ctx }) => {
  expect(last(ctx).json?.wpPostId).toBe((ctx.firstReviewResponse as ApiResponse).json?.wpPostId);
});

Then(
  /^レビューの応答の状態は「(.+)」で、レビュー実施者は管理者のLet's Blogユーザーである$/,
  async ({ ctx, request }, state: string) => {
    expect(last(ctx).json?.state, `状態が違う: ${last(ctx).text}`).toBe(state);
    expect(last(ctx).json?.reviewedByUserId).toBe(await adminUserId(request));
  }
);

Then(
  /^そのPRのレビュー記録の状態は「(.+)」で、テスト環境の投稿URLは応答のURLと一致し、レビュー実施者は管理者である$/,
  async ({ ctx, request }, state: string) => {
    const [dbState, dbUrl, dbReviewer] = readReviewRow(plan(ctx).projectId, review(ctx).prNumber);
    expect(dbState).toBe(state);
    expect(dbUrl).toBe(last(ctx).json?.testPostUrl);
    expect(Number(dbReviewer)).toBe(await adminUserId(request));
  }
);

Then(/^そのPRのレビュー記録の状態は「(.+)」である$/, async ({ ctx }, state: string) => {
  const [dbState, dbUrl, dbReviewer] = readReviewRow(plan(ctx).projectId, review(ctx).prNumber);
  expect(dbState).toBe(state);
  expect(dbUrl, '状態が進んでいないのにテスト環境URLが記録されている').toBe('');
  expect(dbReviewer, '状態が進んでいないのにレビュー実施者が記録されている').toBe('');
});
