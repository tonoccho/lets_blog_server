import { execFileSync } from 'node:child_process';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { STUB_URLS } from '../support/stubs';

/**
 * レビュー完了 API(本番環境へ投稿し、PR をマージしてブランチを削除する)の受け入れシナリオを支えるステップ定義(issue #1343)。
 *
 * プロジェクトは `articlePlan.steps.ts` と同じ形(`ctx.plan`)で作り、`@plan` の After が後片付けする。
 * ただし本番環境のサイトは `ctx.plan.siteId`(テスト環境用)ではないので、このファイルの After で別に消す。
 * 提出済みの PR は `ctx.reviewFixture` に置き、「レビュー中」の状態は他のステップ定義
 * (`articleReviewReject.steps.ts` の「そのPRのレビュー状態がレビュー中である」)が `article_reviews` へ直接作る。
 * 兄弟 issue のステップ定義とは共有せず、必要なヘルパーはこのファイルに閉じて持つ。
 *
 * - GitHub スタブへ PR・ブランチ・ファイルを一意な名前で作り、提出 API で `article_reviews` の行を作る。
 * - マージ・ブランチ削除はスタブの PR 詳細・branches API で、本番投稿は wp-cli で、記録は
 *   `lbs_publishing.article_reviews` を `lbs-mysql` から直接読んで確かめる。
 */

const REPO = '/repos/e2e-stub/acceptance';
const STUB_REPOSITORY = 'e2e-stub/acceptance';
const STUB_TOKEN = 'e2e-stub-token';
const WORDPRESS_CONTAINER = 'lbs-wordpress';
const MYSQL_CONTAINER = 'lbs-mysql';
/** 1x1 の透明 PNG。画像のリサイズ・アップロードを実際に通すため、本物の PNG を使う。 */
const TINY_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';

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

interface PrOptions {
  /** front matter の status。省略すると status 行を書かない(既定の draft を確かめる)。 */
  status?: string;
  scheduledAt?: string;
  conflict?: boolean;
  broken?: boolean;
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

/**
 * スラッグは固定のため、本文は実行ごとに一意にする(issue #1612)。レビュー完了でスタブの base へマージされた
 * 記事と同一内容だと、スタブの changedFiles() が「変更なし」とみなして 404 を返し、再実行できなくなる。
 */
function articleMarkdown(slug: string, title: string, body: string, options: PrOptions): string {
  return [
    '---',
    `title: ${title}`,
    `slug: ${slug}`,
    ...(options.status ? [`status: ${options.status}`] : []),
    ...(options.scheduledAt ? [`publish_scheduled_at: ${options.scheduledAt}`] : []),
    'featured_image: assets/cover.png',
    '---',
    body,
    '',
  ].join('\n');
}

function projectId(ctx: Record<string, unknown>): number {
  const fixture = ctx.plan as { projectId: number } | undefined;
  if (!fixture) {
    throw new Error('先にプロジェクトを用意するステップを実行すること');
  }
  return fixture.projectId;
}

function review(ctx: Record<string, unknown>): ReviewFixture {
  const fixture = ctx.reviewFixture as ReviewFixture | undefined;
  if (!fixture) {
    throw new Error('先に提出済みのPRを用意するステップを実行すること');
  }
  return fixture;
}

function last(ctx: Record<string, unknown>): ApiResponse {
  const response = ctx.approveResponse as ApiResponse | undefined;
  if (!response) {
    throw new Error('先にレビューを完了するステップを実行すること');
  }
  return response;
}

function siteKeyOf(ctx: Record<string, unknown>): string {
  const key = ctx.approveSiteKey as string | undefined;
  if (!key) {
    throw new Error('本番環境のサイトが用意されていない');
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

function mysql(sql: string): string {
  const password = execFileSync('docker', ['exec', MYSQL_CONTAINER, 'printenv', 'MYSQL_ROOT_PASSWORD'], {
    encoding: 'utf8',
    timeout: 30_000,
  }).trim();
  return execFileSync(
    'docker',
    ['exec', '-e', `MYSQL_PWD=${password}`, MYSQL_CONTAINER, 'mysql', '-uroot', '-N', '-B', '-e', sql],
    { encoding: 'utf8', timeout: 60_000 }
  ).replace(/[\r\n]+$/, ''); // 末尾の空フィールド(タブ)を落とさないよう trim() は使わない
}

/** `lbs_publishing.article_reviews` の1行を [state, production_post_url] で返す。 */
function readReviewRow(pid: number, pr: number): string[] {
  const out = mysql(
    'SELECT state, IFNULL(production_post_url, "") FROM lbs_publishing.article_reviews ' +
      `WHERE project_id = ${pid} AND github_pr_number = ${pr}`
  );
  expect(out, `article_reviews に行が無い(project=${pid}, pr=${pr})`).not.toBe('');
  return out.split('\t');
}

async function createProject(request: APIRequestContext, token: string): Promise<number> {
  const suffix = unique();
  const response = await request.post('/api/projects', {
    headers: { Authorization: `Bearer ${token}` },
    data: { name: `E2E 1343 ${suffix}`, slug: `e2e-1343-${suffix}` },
  });
  expect(
    response.ok(),
    `レビュー用プロジェクトの作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return ((await response.json()) as { id: number }).id;
}

async function linkGithub(request: APIRequestContext, token: string, pid: number): Promise<void> {
  const headers = { Authorization: `Bearer ${token}` };
  const repo = await request.put(`/api/projects/${pid}/github-repository`, {
    headers,
    data: { githubRepository: STUB_REPOSITORY },
  });
  expect(repo.ok(), `GitHubリポジトリの紐付けに失敗 (status=${repo.status()}): ${await repo.text()}`).toBe(true);
  const key = await request.put(`/api/projects/${pid}/api-keys/github-token`, {
    headers,
    data: { githubToken: STUB_TOKEN },
  });
  expect(key.ok(), `GitHubトークンの設定に失敗 (status=${key.status()}): ${await key.text()}`).toBe(true);
}

/** 本番環境用のマネージドWordPressを作り、プロジェクトの本番環境へ紐づける。 */
async function createProductionSite(
  request: APIRequestContext, token: string, pid: number
): Promise<{ siteId: number; siteKey: string }> {
  const siteKey = `at1343${unique()}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const created = await request.post('/api/sites/managed-wordpress', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name: `E2E 1343 ${siteKey}`,
      siteKey,
      title: 'E2E 1343 production environment',
      adminUser: 'at1343admin',
      adminEmail: 'at1343@letsblog.local',
      adminPassword: 'At1343Fixture!Pass123',
      locale: 'ja',
    },
    timeout: 120_000,
  });
  expect(
    created.ok(),
    `本番環境サイトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const siteId = ((await created.json()) as { id: number }).id;
  const bound = await request.post(`/api/projects/${pid}/environments`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { environment: 'production', siteId },
  });
  expect(
    bound.ok(),
    `本番環境への紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`
  ).toBe(true);
  return { siteId, siteKey };
}

/** 記事を含むPRをスタブに作り、提出APIで `article_reviews` の行を作る。 */
async function prepareSubmittedPr(
  ctx: Record<string, unknown>, request: APIRequestContext, slug: string, title: string, options: PrOptions = {}
): Promise<void> {
  const head = `article/e2e-approve${options.conflict ? '-conflict-' : '-'}${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const created = await stub('POST', `${REPO}/pulls`, {
    title: `E2Eスタブ: ${head}`, head, base: 'main', body: 'レビュー完了APIの受け入れテスト',
  });
  expect(created.status, `スタブへのPR作成に失敗: ${JSON.stringify(created.json)}`).toBe(201);
  const body = options.broken
    ? '[recharts]\n| a | b |\n|---|---|\n| x | 1 |\n[/recharts]'
    : `# 見出し\n\nレビュー完了APIの本文です\n\n<!-- run: ${head} -->`;
  await putFileBase64(
    head, `articles/${slug}/article.md`,
    Buffer.from(articleMarkdown(slug, title, body, options), 'utf8').toString('base64')
  );
  await putFileBase64(head, `articles/${slug}/assets/cover.png`, TINY_PNG_BASE64);

  const token = await adminToken(request);
  const submitted = await call(
    await request.post(`/api/projects/${projectId(ctx)}/article-review/submissions`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { headBranch: head, githubIssueNumber: 101, articleSlug: slug },
    })
  );
  expect(submitted.status, `提出に失敗した: ${submitted.text}`).toBe(200);
  expect(submitted.json.prNumber, '提出がスタブ上のPRを引き当てていない').toBe(created.json.number);
  ctx.reviewFixture = { prNumber: created.json.number, head, slug } satisfies ReviewFixture;
}

async function approve(ctx: Record<string, unknown>, request: APIRequestContext): Promise<ApiResponse> {
  const token = await adminToken(request);
  return call(
    await request.post(
      `/api/projects/${projectId(ctx)}/article-review/pull-requests/${review(ctx).prNumber}/approve`,
      { headers: { Authorization: `Bearer ${token}` }, timeout: 180_000 }
    )
  );
}

function postIdsBySlug(siteKey: string, slug: string): string[] {
  const out = wpCli(siteKey, ['post', 'list', `--name=${slug}`, '--post_type=post', '--post_status=any', '--format=ids']);
  return out === '' ? [] : out.split(/\s+/);
}

// ------------------------------------------------------------ 前提

Given('GitHub連携と本番環境のサイトが設定されたプロジェクトが用意されている', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const pid = await createProject(request, token);
  ctx.plan = { projectId: pid };
  await linkGithub(request, token, pid);
  const site = await createProductionSite(request, token, pid);
  ctx.approveSiteId = site.siteId;
  ctx.approveSiteKey = site.siteKey;
});

Given(
  /^公開ステータス「(.+)」の記事「(.+)」のtitleが「(.+)」である提出済みのPRがスタブに用意されている$/,
  async ({ ctx, request }, status: string, slug: string, title: string) => {
    await prepareSubmittedPr(ctx, request, slug, title, { status });
  }
);

Given(
  /^予約日時「(.+)」・公開ステータス「(.+)」の記事「(.+)」のtitleが「(.+)」である提出済みのPRがスタブに用意されている$/,
  async ({ ctx, request }, scheduledAt: string, status: string, slug: string, title: string) => {
    await prepareSubmittedPr(ctx, request, slug, title, { status, scheduledAt });
  }
);

Given(
  /^公開ステータス未指定の記事「(.+)」のtitleが「(.+)」である提出済みのPRがスタブに用意されている$/,
  async ({ ctx, request }, slug: string, title: string) => {
    await prepareSubmittedPr(ctx, request, slug, title);
  }
);

Given(
  /^本番投稿に失敗する\(本文に不正なrechartsタグを含む\)記事「(.+)」の提出済みのPRがスタブに用意されている$/,
  async ({ ctx, request }, slug: string) => {
    await prepareSubmittedPr(ctx, request, slug, '壊れた記事', { status: 'publish', broken: true });
  }
);

Given(
  /^コンフリクトしている記事「(.+)」のtitleが「(.+)」である提出済みのPRがスタブに用意されている$/,
  async ({ ctx, request }, slug: string, title: string) => {
    await prepareSubmittedPr(ctx, request, slug, title, { status: 'publish', conflict: true });
  }
);

Given('管理者がそのPRのレビューを完了済みである', async ({ ctx, request }) => {
  const first = await approve(ctx, request);
  expect(first.status, `1回目のレビュー完了に失敗した: ${first.text}`).toBe(200);
});

// ------------------------------------------------------------ 操作

When('管理者がそのPRのレビューを完了する', async ({ ctx, request }) => {
  ctx.approveResponse = await approve(ctx, request);
});

After({ tags: '@plan' }, async ({ ctx, request }) => {
  const siteId = ctx.approveSiteId as number | undefined;
  if (siteId === undefined) {
    return;
  }
  const token = await adminToken(request);
  await request.delete(`/api/sites/${siteId}`, { headers: { Authorization: `Bearer ${token}` }, timeout: 120_000 });
});

// ------------------------------------------------------------ 検証

Then('レビューの完了は成功する', async ({ ctx }) => {
  expect(last(ctx).status, `レビューの完了に失敗した: ${last(ctx).text}`).toBe(200);
});

Then(/^レビューの完了は「(\d+)」で失敗し、エラーに「(.+)」が含まれる$/, async ({ ctx }, status: string, fragment: string) => {
  expect(last(ctx).status, `期待した失敗にならなかった: ${last(ctx).text}`).toBe(Number(status));
  expect(last(ctx).text).toContain(fragment);
});

Then(/^レビューの完了は「(\d+)」で失敗する$/, async ({ ctx }, status: string) => {
  expect(last(ctx).status, `期待した失敗にならなかった: ${last(ctx).text}`).toBe(Number(status));
});

Then(
  /^本番環境のサイトにスラッグ「(.+)」の記事が「(.+)」状態で1件あり、titleは「(.+)」である$/,
  async ({ ctx }, slug: string, status: string, title: string) => {
    const ids = postIdsBySlug(siteKeyOf(ctx), slug);
    expect(ids, `本番環境のサイトのスラッグ「${slug}」の記事数`).toHaveLength(1);
    expect(wpCli(siteKeyOf(ctx), ['post', 'get', ids[0], '--field=post_status'])).toBe(status);
    expect(wpCli(siteKeyOf(ctx), ['post', 'get', ids[0], '--field=post_title'])).toBe(title);
    ctx.approvePostId = ids[0];
  }
);

Then(/^本番環境のサイトにスラッグ「(.+)」の記事は存在しない$/, async ({ ctx }, slug: string) => {
  expect(postIdsBySlug(siteKeyOf(ctx), slug)).toHaveLength(0);
});

Then('レビュー完了の応答の本番の投稿URLは本番環境のその記事のURLと一致する', async ({ ctx }) => {
  const url = last(ctx).json?.productionPostUrl as string | undefined;
  expect(url, `応答に本番の投稿URLが無い: ${last(ctx).text}`).toBeTruthy();
  expect(url).toBe(wpCli(siteKeyOf(ctx), ['post', 'get', ctx.approvePostId as string, '--field=url']));
});

Then('スタブのそのPRはマージ済みである', async ({ ctx }) => {
  const res = await stub('GET', `${REPO}/pulls/${review(ctx).prNumber}`);
  expect(res.json.merged).toBe(true);
  expect(res.json.state).toBe('closed');
});

Then('スタブのそのPRはマージされていない', async ({ ctx }) => {
  const res = await stub('GET', `${REPO}/pulls/${review(ctx).prNumber}`);
  expect(res.json.merged).toBe(false);
  expect(res.json.state).toBe('open');
});

Then('スタブにそのPRのheadブランチは存在しない', async ({ ctx }) => {
  const res = await stub('GET', `${REPO}/branches/${review(ctx).head}`);
  expect(res.status).toBe(404);
});

Then('スタブにそのPRのheadブランチは存在する', async ({ ctx }) => {
  const res = await stub('GET', `${REPO}/branches/${review(ctx).head}`);
  expect(res.status).toBe(200);
});

Then(
  /^そのPRのレビュー記録の状態は「(.+)」で、本番の投稿URLは応答のURLと一致する$/,
  async ({ ctx }, state: string) => {
    const [dbState, dbUrl] = readReviewRow(projectId(ctx), review(ctx).prNumber);
    expect(dbState).toBe(state);
    expect(dbUrl).toBe(last(ctx).json?.productionPostUrl);
  }
);

Then(
  /^そのPRのレビュー記録の状態は「(.+)」で、本番の投稿URLは記録されていない$/,
  async ({ ctx }, state: string) => {
    const [dbState, dbUrl] = readReviewRow(projectId(ctx), review(ctx).prNumber);
    expect(dbState).toBe(state);
    expect(dbUrl, '公開されていないのに本番の投稿URLが記録されている').toBe('');
  }
);

Then(/^そのPRのレビュー記録の状態は「(.+)」のままである$/, async ({ ctx }, state: string) => {
  expect(readReviewRow(projectId(ctx), review(ctx).prNumber)[0]).toBe(state);
});
