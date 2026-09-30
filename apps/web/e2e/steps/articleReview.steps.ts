import type { APIRequestContext } from '@playwright/test';
import { Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';

/**
 * レビュー待ち Pull Request 一覧 API の受け入れシナリオを支えるステップ定義(issue #1337)。
 *
 * プロジェクトのフィクスチャ(GitHub連携あり/なし/無効トークン)は記事プランの
 * `articlePlan.steps.ts` が `ctx.plan` に用意し、`@plan` の After が後片付けする。
 * ここでは API を叩いて応答を確かめることだけを担う。
 */

interface ReviewResponse {
  status: number;
  text: string;
  json: unknown;
}

async function fetchPullRequests(
  request: APIRequestContext,
  projectId: number,
  email: string,
  password: string
): Promise<ReviewResponse> {
  const token = await fetchAccessToken(request, email, password);
  const response = await request.get(`/api/projects/${projectId}/article-review/pull-requests`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const text = await response.text();
  let json: unknown = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  return { status: response.status(), text, json };
}

function projectIdOf(ctx: Record<string, unknown>): number {
  const plan = ctx.plan as { projectId: number } | undefined;
  if (!plan) {
    throw new Error('先にプロジェクトを用意するステップを実行すること');
  }
  return plan.projectId;
}

function lastResponse(ctx: Record<string, unknown>): ReviewResponse {
  const last = ctx.reviewResponse as ReviewResponse | undefined;
  if (!last) {
    throw new Error('先にレビュー待ちPRの一覧を取得するステップを実行すること');
  }
  return last;
}

interface PullRequestItem {
  number: number;
  title: string;
  headBranch: string;
  url: string;
}

function items(ctx: Record<string, unknown>): PullRequestItem[] {
  const last = lastResponse(ctx);
  expect(Array.isArray(last.json), `一覧が配列で返っていない: ${last.text}`).toBe(true);
  return last.json as PullRequestItem[];
}

When('管理者がレビュー待ちPRの一覧を取得する', async ({ ctx, request }) => {
  ctx.reviewResponse = await fetchPullRequests(request, projectIdOf(ctx), E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
});

When('メンバーではない一般利用者がレビュー待ちPRの一覧を取得する', async ({ ctx, request }) => {
  ctx.reviewResponse = await fetchPullRequests(request, projectIdOf(ctx), E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
});

Then('レビュー待ちPRの一覧は成功する', async ({ ctx }) => {
  const last = lastResponse(ctx);
  expect(last.status, `一覧の取得に失敗した: ${last.text}`).toBe(200);
});

Then(
  /^一覧にシードのPR「(\d+)」が番号・タイトル・headブランチ名・URL付きで含まれる$/,
  async ({ ctx }, number: string) => {
    const found = items(ctx).find((pr) => pr.number === Number(number));
    expect(found, `PR #${number} が一覧に無い: ${lastResponse(ctx).text}`).toBeDefined();
    expect(found!.title).toContain('記事サンプル');
    expect(found!.headBranch).toBe('article/e2e-sample');
    expect(found!.url).toContain(`/pull/${number}`);
  }
);

Then(/^一覧にシードのPR「(\d+)」は含まれない$/, async ({ ctx }, number: string) => {
  expect(items(ctx).map((pr) => pr.number)).not.toContain(Number(number));
});

Then(
  /^レビュー待ちPRの一覧は「(\d+)」で失敗し、エラーに「(.+)」が含まれる$/,
  async ({ ctx }, status: string, fragment: string) => {
    const last = lastResponse(ctx);
    expect(last.status, `期待した失敗にならなかった: ${last.text}`).toBe(Number(status));
    expect(last.text).toContain(fragment);
  }
);

Then(/^レビュー待ちPRの一覧は「(\d+)」で失敗する$/, async ({ ctx }, status: string) => {
  const last = lastResponse(ctx);
  expect(last.status, `期待した失敗にならなかった: ${last.text}`).toBe(Number(status));
});

Then('レビュー待ちPRの一覧は404ではなくJSON配列で返る', async ({ ctx }) => {
  const last = lastResponse(ctx);
  expect(last.status, `project-service の 404 に落ちている: ${last.text}`).not.toBe(404);
  expect(Array.isArray(last.json), `JSON配列で返っていない: ${last.text}`).toBe(true);
});
