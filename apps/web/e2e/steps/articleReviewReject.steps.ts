import { execFileSync } from 'node:child_process';
import type { APIRequestContext } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { STUB_URLS } from '../support/stubs';

/**
 * 記事差し戻し API(指摘を Pull Request コメントとして記録する)の受け入れシナリオを支えるステップ定義(issue #1344)。
 *
 * プロジェクトと提出済みの PR は `articlePlan.steps.ts` / `articleReviewPublish.steps.ts` の前提ステップが
 * `ctx.plan` / `ctx.reviewFixture` に用意する(後片付けは `@plan` の After)。ここでは
 * - 「レビュー中」の状態を `lbs_publishing.article_reviews` へ直接作る(#1341 が検証済みの遷移を毎回
 *   テスト環境のサイトを作って辿らないため)、
 * - 他の利用者が提出した行を直接挿入する(E2E の利用者は管理者 1 人のため)、
 * - 投稿されたコメントはスタブの comments API から、記録は `article_reviews` から確かめる。
 */

const REPO = '/repos/e2e-stub/acceptance';
const STUB_TOKEN = 'e2e-stub-token';
const MYSQL_CONTAINER = 'lbs-mysql';
/** 管理者とは別の利用者として挿入する提出者の ID の増分。 */
const OTHER_USER_OFFSET = 900_000;

/** 差し戻し応答の JSON。一覧(配列)の応答は呼び出し側で ReviewRow[] として扱う。 */
interface ResponseJson {
  state: string;
  commentId: number;
}

interface ReviewRow {
  articleSlug: string;
  state: string;
  rejectComment: string;
  rejectedAt?: string;
}

interface ApiResponse {
  status: number;
  text: string;
  json: ResponseJson;
}

function projectId(ctx: Record<string, unknown>): number {
  const fixture = ctx.plan as { projectId: number } | undefined;
  if (!fixture) {
    throw new Error('先にプロジェクトを用意するステップを実行すること');
  }
  return fixture.projectId;
}

function prNumber(ctx: Record<string, unknown>): number {
  const fixture = ctx.reviewFixture as { prNumber: number } | undefined;
  if (!fixture) {
    throw new Error('先に提出済みのPRを用意するステップを実行すること');
  }
  return fixture.prNumber;
}

function rejectResult(ctx: Record<string, unknown>): ApiResponse {
  const response = ctx.rejectResponse as ApiResponse | undefined;
  if (!response) {
    throw new Error('先に差し戻しを実行するステップを実行すること');
  }
  return response;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function adminUserId(request: APIRequestContext): Promise<number> {
  const token = await adminToken(request);
  const response = await request.get('/api/identity/me', { headers: { Authorization: `Bearer ${token}` } });
  expect(response.ok(), `本人情報の取得に失敗した (status=${response.status()})`).toBe(true);
  return ((await response.json()) as { id: number }).id;
}

async function call(response: Awaited<ReturnType<APIRequestContext['post']>>): Promise<ApiResponse> {
  const text = await response.text();
  let json: ResponseJson | null = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  return { status: response.status(), text, json: json as ResponseJson };
}

async function stubComments(pr: number): Promise<Array<{ id: number; body: string }>> {
  const res = await fetch(`${STUB_URLS.github}${REPO}/issues/${pr}/comments`, {
    headers: { Authorization: `Bearer ${STUB_TOKEN}` },
  });
  expect(res.status, 'スタブのコメント一覧の取得に失敗').toBe(200);
  return (await res.json()) as Array<{ id: number; body: string }>;
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

function readRow(pid: number, pr: number): string[] {
  const out = mysql(
    'SELECT state, IFNULL(rejected_by_user_id, ""), IFNULL(rejected_at, ""), IFNULL(reject_comment_id, "") ' +
      `FROM lbs_publishing.article_reviews WHERE project_id = ${pid} AND github_pr_number = ${pr}`
  );
  expect(out, `article_reviews に行が無い(project=${pid}, pr=${pr})`).not.toBe('');
  return out.split('\t');
}

async function reject(ctx: Record<string, unknown>, request: APIRequestContext, comment: string): Promise<void> {
  const token = await adminToken(request);
  ctx.rejectResponse = await call(
    await request.post(
      `/api/projects/${projectId(ctx)}/article-review/pull-requests/${prNumber(ctx)}/reject`,
      { headers: { Authorization: `Bearer ${token}` }, data: { comment } }
    )
  );
}

// ------------------------------------------------------------ 前提

Given('そのPRのレビュー状態がレビュー中である', async ({ ctx }) => {
  mysql(
    "UPDATE lbs_publishing.article_reviews SET state = 'IN_REVIEW' " +
      `WHERE project_id = ${projectId(ctx)} AND github_pr_number = ${prNumber(ctx)}`
  );
});

Given(/^他の利用者が提出した記事「(.+)」のレビュー記録がある$/, async ({ ctx, request }, slug: string) => {
  const other = (await adminUserId(request)) + OTHER_USER_OFFSET;
  mysql(
    'INSERT INTO lbs_publishing.article_reviews ' +
      '(project_id, github_pr_number, github_issue_number, article_slug, submitted_by_user_id, state, ' +
      'submitted_at, created_at, updated_at) ' +
      `VALUES (${projectId(ctx)}, ${prNumber(ctx) + 100_000}, 102, '${slug}', ${other}, 'SUBMITTED', ` +
      'NOW(6), NOW(6), NOW(6))'
  );
});

// ------------------------------------------------------------ 操作

When(/^管理者がそのPRを「(.+)」という指摘で差し戻す$/, async ({ ctx, request }, comment: string) => {
  await reject(ctx, request, comment);
});

When('管理者がそのPRを空白だけの指摘で差し戻す', async ({ ctx, request }) => {
  await reject(ctx, request, '   ');
});

When('管理者が自分宛のレビュー一覧を取得する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  ctx.myReviewsResponse = await call(
    await request.get(`/api/projects/${projectId(ctx)}/article-review/my-reviews`, {
      headers: { Authorization: `Bearer ${token}` },
    })
  );
});

// ------------------------------------------------------------ 検証

Then('差し戻しは成功する', async ({ ctx }) => {
  expect(rejectResult(ctx).status, `差し戻しに失敗した: ${rejectResult(ctx).text}`).toBe(200);
});

Then(/^差し戻しは「(\d+)」で失敗する$/, async ({ ctx }, status: string) => {
  expect(rejectResult(ctx).status, `期待した失敗にならなかった: ${rejectResult(ctx).text}`).toBe(Number(status));
});

Then(/^スタブのそのPRのコメントに「(.+)」が1件投稿されている$/, async ({ ctx }, text: string) => {
  const matching = (await stubComments(prNumber(ctx))).filter((c) => c.body.includes(text));
  expect(matching, `スタブのコメントに「${text}」が1件投稿されていない`).toHaveLength(1);
});

Then('スタブのそのPRにコメントは投稿されていない', async ({ ctx }) => {
  expect(await stubComments(prNumber(ctx))).toHaveLength(0);
});

Then(
  "スタブに投稿された差し戻しコメントの本文に、管理者のLet's BlogユーザーのメールアドレスとユーザーIDが含まれる",
  async ({ ctx, request }) => {
    const comments = await stubComments(prNumber(ctx));
    expect(comments).toHaveLength(1);
    expect(comments[0].body).toContain(E2E_ADMIN_EMAIL);
    expect(comments[0].body).toContain(`ID ${await adminUserId(request)}`);
  }
);

Then(/^差し戻しの応答の状態は「(.+)」である$/, async ({ ctx }, state: string) => {
  expect(rejectResult(ctx).json?.state, `状態が違う: ${rejectResult(ctx).text}`).toBe(state);
});

Then(
  /^そのPRのレビュー記録の状態は「(.+)」で、差し戻し実施者は管理者であり、差し戻し時刻とコメントIDが応答と一致して記録されている$/,
  async ({ ctx, request }, state: string) => {
    const [dbState, dbBy, dbAt, dbCommentId] = readRow(projectId(ctx), prNumber(ctx));
    expect(dbState).toBe(state);
    expect(Number(dbBy)).toBe(await adminUserId(request));
    expect(dbAt, '差し戻し時刻が記録されていない').not.toBe('');
    expect(Number(dbCommentId)).toBe(rejectResult(ctx).json?.commentId);
    const comments = await stubComments(prNumber(ctx));
    expect(comments.map((c) => c.id)).toContain(Number(dbCommentId));
  }
);

Then(/^そのPRのレビュー記録の状態は「(.+)」のままで、差し戻しの記録はない$/, async ({ ctx }, state: string) => {
  const [dbState, dbBy, , dbCommentId] = readRow(projectId(ctx), prNumber(ctx));
  expect(dbState).toBe(state);
  expect(dbBy, '差し戻していないのに実施者が記録されている').toBe('');
  expect(dbCommentId, '差し戻していないのにコメントIDが記録されている').toBe('');
});

Then(
  /^一覧には記事「(.+)」が状態「(.+)」で含まれ、指摘事項「(.+)」と差し戻し時刻が付いている$/,
  async ({ ctx }, slug: string, state: string, comment: string) => {
    const response = ctx.myReviewsResponse as ApiResponse;
    expect(response.status, `一覧の取得に失敗した: ${response.text}`).toBe(200);
    const row = (response.json as unknown as ReviewRow[]).find((r) => r.articleSlug === slug);
    expect(row, `一覧に記事「${slug}」が無い: ${response.text}`).toBeTruthy();
    expect(row!.state).toBe(state);
    expect(row!.rejectComment).toContain(comment);
    expect(row!.rejectedAt, '差し戻し時刻が無い').toBeTruthy();
  }
);

Then(/^一覧に記事「(.+)」は含まれない$/, async ({ ctx }, slug: string) => {
  const response = ctx.myReviewsResponse as ApiResponse;
  expect((response.json as unknown as ReviewRow[]).map((r) => r.articleSlug)).not.toContain(slug);
});
