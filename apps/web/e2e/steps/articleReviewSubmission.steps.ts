import type { APIRequestContext } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';
import { STUB_URLS } from '../support/stubs';

/**
 * 記事提出 API の受け入れシナリオを支えるステップ定義(issue #1339)。
 *
 * プロジェクトのフィクスチャは記事プランの `articlePlan.steps.ts`(`ctx.plan`)が用意する。
 * 「プッシュ済みで開いている PR が無いブランチ」は、スタブへ一意なブランチを PR 作成で作り、
 * そのPRをマージして閉じることで用意する(スタブにはブランチを直接作る API が無いが、
 * PR 作成はヘッド不在なら空のブランチを作り、マージは PR を閉じてブランチを残す)。
 */

const REPO = '/repos/e2e-stub/acceptance';
const STUB_TOKEN = 'e2e-stub-token';

interface SubmissionResponse {
  status: number;
  text: string;
  json: any;
}

interface BranchFixture {
  head: string;
  firstPrNumber?: number;
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

function uniqueHead(): string {
  return `article/e2e-submit-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
}

function projectIdOf(ctx: Record<string, unknown>): number {
  const plan = ctx.plan as { projectId: number } | undefined;
  if (!plan) {
    throw new Error('先にプロジェクトを用意するステップを実行すること');
  }
  return plan.projectId;
}

function branchOf(ctx: Record<string, unknown>): BranchFixture {
  const branch = ctx.submissionBranch as BranchFixture | undefined;
  if (!branch) {
    throw new Error('先にブランチをスタブに用意するステップを実行すること');
  }
  return branch;
}

function lastOf(ctx: Record<string, unknown>): SubmissionResponse {
  const last = ctx.submissionResponse as SubmissionResponse | undefined;
  if (!last) {
    throw new Error('先に提出するステップを実行すること');
  }
  return last;
}

async function submit(
  request: APIRequestContext,
  projectId: number,
  head: string,
  issueNumber: number,
  slug: string,
  email: string,
  password: string
): Promise<SubmissionResponse> {
  const token = await fetchAccessToken(request, email, password);
  const response = await request.post(`/api/projects/${projectId}/article-review/submissions`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { headBranch: head, githubIssueNumber: issueNumber, articleSlug: slug },
  });
  const text = await response.text();
  let json: any = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  return { status: response.status(), text, json };
}

async function adminUserId(request: APIRequestContext): Promise<number> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const response = await request.get('/api/identity/me', { headers: { Authorization: `Bearer ${token}` } });
  expect(response.ok(), `本人情報の取得に失敗した (status=${response.status()})`).toBe(true);
  return ((await response.json()) as { id: number }).id;
}

async function openPullRequestsFor(head: string): Promise<any[]> {
  const res = await stub('GET', `${REPO}/pulls?state=open`);
  return (res.json as any[]).filter((pr) => pr.head.ref === head);
}

Given('開いているPRが無いプッシュ済みのブランチがスタブに用意されている', async ({ ctx }) => {
  const head = uniqueHead();
  const created = await stub('POST', `${REPO}/pulls`, {
    title: `E2Eスタブ: ${head}`, head, base: 'main', body: '提出APIの受け入れテスト用の過去のPR',
  });
  expect(created.status, `スタブへのPR作成に失敗: ${JSON.stringify(created.json)}`).toBe(201);
  const merged = await stub('PUT', `${REPO}/pulls/${created.json.number}/merge`, {});
  expect(merged.status, `スタブでのPRマージに失敗: ${JSON.stringify(merged.json)}`).toBe(200);
  ctx.submissionBranch = { head } satisfies BranchFixture;
});

Given(
  /^管理者がそのブランチをIssue番号「(\d+)」記事スラッグ「(.+)」で提出済みである$/,
  async ({ ctx, request }, issue: string, slug: string) => {
    const branch = branchOf(ctx);
    const first = await submit(
      request, projectIdOf(ctx), branch.head, Number(issue), slug, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
    expect(first.status, `1回目の提出に失敗した: ${first.text}`).toBe(200);
    branch.firstPrNumber = first.json.prNumber;
  }
);

When(
  /^管理者がそのブランチをIssue番号「(\d+)」記事スラッグ「(.+)」で提出する$/,
  async ({ ctx, request }, issue: string, slug: string) => {
    ctx.submissionResponse = await submit(
      request, projectIdOf(ctx), branchOf(ctx).head, Number(issue), slug, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  }
);

When(
  /^管理者が存在しないブランチをIssue番号「(\d+)」記事スラッグ「(.+)」で提出する$/,
  async ({ ctx, request }, issue: string, slug: string) => {
    ctx.submissionResponse = await submit(
      request, projectIdOf(ctx), `article/e2e-missing-${Date.now()}`, Number(issue), slug,
      E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  }
);

When(
  /^メンバーではない一般利用者がそのブランチをIssue番号「(\d+)」記事スラッグ「(.+)」で提出する$/,
  async ({ ctx, request }, issue: string, slug: string) => {
    ctx.submissionResponse = await submit(
      request, projectIdOf(ctx), branchOf(ctx).head, Number(issue), slug, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
  }
);

Then('提出は成功する', async ({ ctx }) => {
  const last = lastOf(ctx);
  expect(last.status, `提出に失敗した: ${last.text}`).toBe(200);
});

Then(/^提出は「(\d+)」で失敗し、エラーに「(.+)」が含まれる$/, async ({ ctx }, status: string, fragment: string) => {
  const last = lastOf(ctx);
  expect(last.status, `期待した失敗にならなかった: ${last.text}`).toBe(Number(status));
  expect(last.text).toContain(fragment);
});

Then(/^提出は「(\d+)」で失敗する$/, async ({ ctx }, status: string) => {
  const last = lastOf(ctx);
  expect(last.status, `期待した失敗にならなかった: ${last.text}`).toBe(Number(status));
});

Then('提出の応答にPR番号とURLが含まれる', async ({ ctx }) => {
  const json = lastOf(ctx).json;
  expect(typeof json.prNumber, `PR番号が無い: ${lastOf(ctx).text}`).toBe('number');
  expect(json.url, `URLが無い: ${lastOf(ctx).text}`).toContain(`/pull/${json.prNumber}`);
});

Then('スタブの開いているPR一覧にそのブランチをheadとするPRが提出の応答のPR番号で含まれる', async ({ ctx }) => {
  const prs = await openPullRequestsFor(branchOf(ctx).head);
  expect(prs.map((pr) => pr.number)).toContain(lastOf(ctx).json.prNumber);
});

Then(/^スタブ上のそのPRの本文に「(.+)」が含まれる$/, async ({ ctx }, fragment: string) => {
  const number = lastOf(ctx).json?.prNumber;
  expect(number, `提出の応答にPR番号が無い: ${lastOf(ctx).text}`).toBeDefined();
  const res = await stub('GET', `${REPO}/pulls/${number}`);
  expect(res.json.body).toContain(fragment);
});

Then('提出の応答の提出者は管理者のLet\'s Blogユーザーである', async ({ ctx, request }) => {
  expect(lastOf(ctx).json?.submittedByUserId, `提出者が無い: ${lastOf(ctx).text}`).toBe(await adminUserId(request));
});

Then(/^提出の応答の状態は「(.+)」である$/, async ({ ctx }, state: string) => {
  expect(lastOf(ctx).json?.state, `状態が違う: ${lastOf(ctx).text}`).toBe(state);
});

Then('提出の応答のPR番号は1回目の提出と同じである', async ({ ctx }) => {
  expect(lastOf(ctx).json?.prNumber).toBe(branchOf(ctx).firstPrNumber);
});

Then('スタブの開いているPR一覧にそのブランチをheadとするPRは1件だけである', async ({ ctx }) => {
  expect(await openPullRequestsFor(branchOf(ctx).head)).toHaveLength(1);
});
