import type { APIRequestContext } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { forceStubStatus } from '../support/stubs';

/**
 * 壁打ちのBrave Search併用とプロジェクト単位APIキー管理のステップ定義
 * (issue #1147 / AT-8-2、issue #934の子)。
 *
 * プロジェクトフィクスチャ(`AI設定用のプロジェクトが用意されている`)と後片付けの
 * Afterフックは `aiGeneration.steps.ts`(issue #1146)がすでに`@ai`向けに定義済みのため、
 * ここでは再定義せず `ctx.aiGenerationProject` を読むだけにする(兄弟issueとのステップ重複を
 * 避けるため、新規ファイルに分離する)。
 *
 * `/api/projects/{projectId}/article-plan/chat` にはWeb管理画面のUI操作(article-plan/の
 * 記事計画画面)もあるが、そちらはGitHub Issue往復(AT-9)の検証観点で別issueが専有している。
 * ここでの関心はWeb検索併用の成否そのものであり、UIを経由しても`ArticlePlanService#chat`が
 * 生成ジョブ(type=plan_chat)へ記録する`webSearchSucceeded`/`webSearchError`は変わらないため、
 * `@api`でgatewayを直接叩いて検証する。
 */

/** シナリオ限りのAI設定用プロジェクト(`aiGeneration.steps.ts`が生成・削除を管理する)。 */
interface AiProjectFixture {
  projectId: number;
}

/** `GET /api/projects/{projectId}/api-keys/brave-search-api-key` の応答(`ProjectApiKeyStatusResponse`)。 */
interface ProjectApiKeyStatus {
  configured: boolean;
}

/** `GET /api/generation-jobs` の1件(`GenerationJobResponse`)。 */
interface GenerationJobSummary {
  id: number;
  type: string;
  status: string;
}

/** `GET /api/generation-jobs/{id}` の詳細(`GenerationJobDetailResponse`)。 */
interface GenerationJobDetail {
  id: number;
  type: string;
  status: string;
  requestPayload: string;
  resultPayload: string;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

function currentProjectId(ctx: Record<string, unknown>): number {
  return (ctx.aiGenerationProject as AiProjectFixture).projectId;
}

// --------------------------------------------------------------- プロジェクト単位のBrave Search APIキー

When(/^そのプロジェクトのBrave Search APIキーを「(.+)」に設定する$/, async ({ ctx, request }, apiKey: string) => {
  const token = await adminToken(request);
  const projectId = currentProjectId(ctx);
  const response = await request.put(`/api/projects/${projectId}/api-keys/brave-search-api-key`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { apiKey },
  });
  expect(
    response.ok(),
    `プロジェクトのBrave Search APIキー設定に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.projectBraveSearchApiKey = apiKey;
});

When('そのプロジェクトのBrave Search APIキーを削除する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = currentProjectId(ctx);
  const response = await request.delete(`/api/projects/${projectId}/api-keys/brave-search-api-key`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.ok(),
    `プロジェクトのBrave Search APIキー削除に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

async function fetchProjectBraveSearchApiKeyStatus(
  request: APIRequestContext,
  token: string,
  projectId: number
): Promise<ProjectApiKeyStatus> {
  const response = await request.get(`/api/projects/${projectId}/api-keys/brave-search-api-key`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.ok(),
    `プロジェクトのBrave Search APIキー状態の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as ProjectApiKeyStatus;
}

Then('そのプロジェクトのBrave Search APIキーは設定済みとして扱われ、値は含まれない', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = currentProjectId(ctx);
  const body = await fetchProjectBraveSearchApiKeyStatus(request, token, projectId);
  expect(body.configured, `Brave Search APIキーの状態: ${JSON.stringify(body)}`).toBe(true);
  const plainKey = ctx.projectBraveSearchApiKey as string;
  expect(JSON.stringify(body), 'Brave Search APIキーの平文がレスポンスに含まれています').not.toContain(plainKey);
});

Then('そのプロジェクトのBrave Search APIキーは未設定として扱われる', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = currentProjectId(ctx);
  const body = await fetchProjectBraveSearchApiKeyStatus(request, token, projectId);
  expect(body.configured, `Brave Search APIキーの状態: ${JSON.stringify(body)}`).toBe(false);
});

// --------------------------------------------------------------- 壁打ち(Web検索併用)

When(/^そのプロジェクトの壁打ちで「(.+)」と発言する$/, async ({ ctx, request }, message: string) => {
  const token = await adminToken(request);
  const projectId = currentProjectId(ctx);
  const response = await request.post(`/api/projects/${projectId}/article-plan/chat`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { history: [], message, sessionId: null, githubIssueNumber: null },
  });
  expect(
    response.ok(),
    `壁打ちの依頼に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.planChatRespondedAt = Date.now();
});

/**
 * `GET /api/generation-jobs` は所有者列を持たず全件を返す(GenerationJobController#list の
 * javadoc参照)ため、type=plan_chat のうち最新の1件を拾う。一覧は既にcreatedAt降順で
 * 返るので、最初に見つかったものが直近のジョブになる。
 */
async function latestPlanChatJob(request: APIRequestContext, token: string): Promise<GenerationJobDetail> {
  const listResponse = await request.get('/api/generation-jobs', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    listResponse.ok(),
    `生成ジョブ一覧の取得に失敗しました (status=${listResponse.status()}): ${await listResponse.text()}`
  ).toBe(true);
  const jobs = (await listResponse.json()) as GenerationJobSummary[];
  const summary = jobs.find((job) => job.type === 'plan_chat');
  expect(summary, 'type=plan_chat の生成ジョブが見つかりません').toBeDefined();

  const detailResponse = await request.get(`/api/generation-jobs/${summary!.id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    detailResponse.ok(),
    `生成ジョブ詳細の取得に失敗しました (status=${detailResponse.status()}): ${await detailResponse.text()}`
  ).toBe(true);
  return (await detailResponse.json()) as GenerationJobDetail;
}

/** `ArticlePlanService#chat` が `completeJob` へ積む `resultPayload`(文字列値のJSON)。 */
interface PlanChatResultPayload {
  reply: string;
  webSearchAttempted: string;
  webSearchSucceeded: string;
  webSearchError: string;
}

Then('直近の壁打ちジョブはWeb検索に成功したと記録されている', async ({ request }) => {
  const token = await adminToken(request);
  const job = await latestPlanChatJob(request, token);
  expect(job.status, `壁打ちジョブの記録: ${JSON.stringify(job)}`).toBe('done');
  const result = JSON.parse(job.resultPayload) as PlanChatResultPayload;
  expect(result.webSearchSucceeded, `壁打ちジョブの検索結果記録: ${JSON.stringify(result)}`).toBe('true');
});

Then('直近の壁打ちジョブはWeb検索なしでフェイルオープンしたと記録されている', async ({ request }) => {
  const token = await adminToken(request);
  const job = await latestPlanChatJob(request, token);
  expect(job.status, `壁打ちジョブの記録: ${JSON.stringify(job)}`).toBe('done');
  const result = JSON.parse(job.resultPayload) as PlanChatResultPayload;
  expect(result.webSearchSucceeded, `壁打ちジョブの検索結果記録: ${JSON.stringify(result)}`).toBe('false');
  expect(result.webSearchError, '検索失敗の理由が記録されていません(フェイルオープンの根拠が無い)').not.toBe('null');
});

// --------------------------------------------------------------- スタブへのエラー注入(制御エンドポイント)

Given('Brave Searchが次のリクエストで500を返すよう仕込む', async () => {
  await forceStubStatus('brave-search', 500, 1);
});
