import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';

/**
 * AI系エンドポイントの認可(非メンバー403)の受け入れシナリオを支えるステップ定義
 * (issue #1150 / AT-8-5、親issue #934のシナリオ18を引き取る子issue)。
 *
 * 未認証(401、親シナリオ17)は `cross-cutting/authorization-matrix.feature` の
 * マトリクス駆動シナリオが既に担保しているため、ここでは扱わない
 * (`ai/authorization.feature` の説明コメント参照)。
 *
 * ## 判定方式が2系統ある
 *
 * `ProjectLlmModelController`(`/ai-models/llm/**`)は `requireAdmin()` —
 * プロジェクトIDを見ないグローバル管理者判定。`ProjectBraveSearchApiKeyController`
 * (`/api-keys/brave-search-api-key`)は `requireProjectMemberOrAdmin(projectId)` —
 * プロジェクト単位のメンバー判定。前者はメンバーであるかどうかに関わらず一般利用者を
 * 拒否するため、後者のような「自分のプロジェクトでは読み書きできる」対照は組めない。
 */

type ScenarioState = Record<string, unknown>;

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

/** 「一般利用者」= 非adminの合成アカウント。プロジェクト単位の仕切りの検証に使う。 */
async function memberToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await adminToken(request)}` };
}

async function memberHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await memberToken(request)}` };
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

interface AiProject {
  id: number;
}

/**
 * サイトを持たないプロジェクト。認可の検証はメンバー判定より手前で完結するため、
 * サイトの紐付けは不要(`analytics.steps.ts` の `createBareProject` と同じ理由)。
 */
async function createBareProject(request: APIRequestContext, ctx: ScenarioState): Promise<AiProject> {
  const suffix = uniqueSuffix();
  const created = await request.post('/api/projects', {
    headers: await adminHeaders(request),
    data: { name: `E2E 1150 ${suffix}`, slug: `e2e-1150-${suffix}` },
  });
  expect(
    created.ok(),
    `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const project: AiProject = { id: ((await created.json()) as { id: number }).id };
  trackedProjects(ctx).push(project);
  return project;
}

function trackedProjects(ctx: ScenarioState): AiProject[] {
  ctx.aiAuthProjects ??= [];
  return ctx.aiAuthProjects as AiProject[];
}

interface DeniedOutcome {
  what: string;
  status: number;
  body: string;
}

async function send(
  pending: ReturnType<APIRequestContext['get']>
): Promise<{ status: number; body: string }> {
  const response = await pending;
  return { status: response.status(), body: await response.text() };
}

/** `/ai-models/llm/**` の4本。GET/PUTすべてを、妥当な本文を添えて叩く。 */
async function requestAllLlmEndpoints(
  request: APIRequestContext,
  projectId: number,
  headers: Record<string, string>
): Promise<DeniedOutcome[]> {
  const base = `/api/projects/${projectId}/ai-models/llm`;
  const calls: { what: string; run: () => Promise<{ status: number; body: string }> }[] = [
    { what: 'GET llm/models', run: () => send(request.get(`${base}/models`, { headers })) },
    {
      what: 'PUT llm/models/selection',
      run: () => send(request.put(`${base}/models/selection`, {
        headers,
        data: { modelName: 'intruder-model' },
      })),
    },
    { what: 'GET llm/provider', run: () => send(request.get(`${base}/provider`, { headers })) },
    {
      what: 'PUT llm/provider/selection',
      run: () => send(request.put(`${base}/provider/selection`, {
        headers,
        data: { provider: 'openai' },
      })),
    },
  ];

  const outcomes: DeniedOutcome[] = [];
  for (const call of calls) {
    outcomes.push({ what: call.what, ...(await call.run()) });
  }
  return outcomes;
}

/** `/api-keys/brave-search-api-key` の3本(GET/PUT/DELETE)。 */
async function requestAllBraveSearchApiKeyEndpoints(
  request: APIRequestContext,
  projectId: number,
  headers: Record<string, string>
): Promise<DeniedOutcome[]> {
  const url = `/api/projects/${projectId}/api-keys/brave-search-api-key`;
  const calls: { what: string; run: () => Promise<{ status: number; body: string }> }[] = [
    { what: 'GET brave-search-api-key', run: () => send(request.get(url, { headers })) },
    {
      what: 'PUT brave-search-api-key',
      run: () => send(request.put(url, { headers, data: { apiKey: 'intruder-brave-search-key' } })),
    },
    { what: 'DELETE brave-search-api-key', run: () => send(request.delete(url, { headers })) },
  ];

  const outcomes: DeniedOutcome[] = [];
  for (const call of calls) {
    outcomes.push({ what: call.what, ...(await call.run()) });
  }
  return outcomes;
}

function seenDenied(ctx: ScenarioState): DeniedOutcome[] {
  const outcomes = ctx.aiAuthDenied as DeniedOutcome[] | undefined;
  if (!outcomes || outcomes.length === 0) {
    throw new Error('先に要求を送るステップを実行すること');
  }
  return outcomes;
}

// ------------------------------------------------------------ LLM設定

Given('一般利用者がメンバーではないプロジェクトがある', async ({ ctx, request }) => {
  ctx.aiAuthLlmProject = await createBareProject(request, ctx);
});

When('一般利用者がそのプロジェクトのLLM設定エンドポイントをすべて要求する', async ({ ctx, request }) => {
  const project = ctx.aiAuthLlmProject as AiProject;
  ctx.aiAuthDenied = await requestAllLlmEndpoints(request, project.id, await memberHeaders(request));
});

Then('LLM設定はすべてプロジェクトメンバーではないとして拒否される', async ({ ctx }) => {
  for (const outcome of seenDenied(ctx)) {
    expect(outcome.status, `${outcome.what} が一般利用者への403で拒否されていない: ${outcome.body}`).toBe(403);
  }
});

// ------------------------------------------------------ Brave Search APIキー

const INTRUDER_BRAVE_SEARCH_API_KEY = 'at1150-owner-brave-search-key';

Given('AI設定を確かめるプロジェクトが2つあり、一般利用者は片方だけのメンバーである', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const memberProject = await createBareProject(request, ctx);
  const otherProject = await createBareProject(request, ctx);

  const me = await request.get('/api/identity/me', { headers: await memberHeaders(request) });
  expect(me.ok(), `一般利用者の情報を取得できませんでした (status=${me.status()})`).toBe(true);
  const memberUserId = ((await me.json()) as { id: number }).id;

  const added = await request.post(`/api/projects/${memberProject.id}/users`, {
    headers,
    data: { userId: memberUserId, wpRole: 'editor' },
  });
  expect(
    added.ok(),
    `プロジェクトメンバーの追加に失敗しました (status=${added.status()}): ${await added.text()}`
  ).toBe(true);

  // 他プロジェクト側には既知のキーを入れておく。書き換えられていないことを後で見る。
  const otherKeySet = await request.put(
    `/api/projects/${otherProject.id}/api-keys/brave-search-api-key`,
    { headers, data: { apiKey: INTRUDER_BRAVE_SEARCH_API_KEY } }
  );
  expect(
    otherKeySet.ok(),
    `他プロジェクトへのBrave Search APIキー登録に失敗しました (status=${otherKeySet.status()})`
  ).toBe(true);

  ctx.aiAuthMemberProject = memberProject;
  ctx.aiAuthOtherProject = otherProject;
});

Then('一般利用者は自分のプロジェクトのBrave Search APIキーを読み書きできる', async ({ ctx, request }) => {
  const project = ctx.aiAuthMemberProject as AiProject;
  const headers = await memberHeaders(request);
  const url = `/api/projects/${project.id}/api-keys/brave-search-api-key`;

  const read = await request.get(url, { headers });
  expect(
    read.status(),
    `メンバーなのに自分のプロジェクトのBrave Search APIキーを読めない: ${await read.text()}`
  ).toBe(200);

  const written = await request.put(url, { headers, data: { apiKey: 'e2e-1150-member-brave-search-key' } });
  expect(
    written.status(),
    `メンバーなのに自分のプロジェクトのBrave Search APIキーを書けない: ${await written.text()}`
  ).toBe(204);

  const after = await request.get(url, { headers });
  expect((await after.json()) as { configured: boolean }).toMatchObject({ configured: true });
});

When('一般利用者が他プロジェクトのBrave Search APIキーエンドポイントをすべて要求する', async ({ ctx, request }) => {
  const project = ctx.aiAuthOtherProject as AiProject;
  ctx.aiAuthDenied = await requestAllBraveSearchApiKeyEndpoints(
    request,
    project.id,
    await memberHeaders(request)
  );
});

Then('Brave Search APIキーはすべてプロジェクトメンバーではないとして拒否される', async ({ ctx }) => {
  for (const outcome of seenDenied(ctx)) {
    expect(outcome.status, `${outcome.what} が非メンバーへの403で拒否されていない: ${outcome.body}`).toBe(403);
  }
});

Then('他プロジェクトのBrave Search APIキーは書き換えられていない', async ({ ctx, request }) => {
  const project = ctx.aiAuthOtherProject as AiProject;
  const status = await request.get(`/api/projects/${project.id}/api-keys/brave-search-api-key`, {
    headers: await adminHeaders(request),
  });
  expect((await status.json()) as { configured: boolean }).toMatchObject({ configured: true });
});

// ------------------------------------------------------------ 後片付け

After({ tags: '@ai' }, async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  for (const project of trackedProjects(ctx)) {
    await request.delete(`/api/projects/${project.id}`, { headers });
  }
});
