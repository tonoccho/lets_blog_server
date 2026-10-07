import type { APIRequestContext, Locator, Page } from '@playwright/test';
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
 * Ollamaのモデルのインストール(pull)(issue #1675)のステップ定義。
 * プロジェクトの用意・設定タブの表示・接続先の上書きは `aiConnectionPanel.steps.ts` の既存ステップを使い、
 * ここには pull 固有の操作と確認だけを置く。他のai系ステップ定義ファイルと同様、ヘルパーはこのファイル内に閉じて持つ。
 */

type PullResponse = { status: number; body: { jobId?: number; alreadyRunning?: boolean } };

const INPUT_LABEL = 'インストールするOllamaモデル名';

async function bearer(request: APIRequestContext, email: string, password: string) {
  return { Authorization: `Bearer ${await fetchAccessToken(request, email, password)}` };
}
const adminHeaders = (request: APIRequestContext) => bearer(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
const memberHeaders = (request: APIRequestContext) => bearer(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);

function section(page: Page): Locator {
  return page.locator('section', { has: page.getByRole('heading', { name: 'Ollamaの接続情報' }) });
}

async function pullViaApi(
  request: APIRequestContext,
  projectId: number,
  headers: Record<string, string>,
  model: string
): Promise<PullResponse> {
  const response = await request.post(`/api/projects/${projectId}/ai-models/ollama/pull`, {
    headers,
    data: { model },
  });
  const text = await response.text();
  let body: PullResponse['body'] = {};
  try {
    body = JSON.parse(text);
  } catch {
    // 4xx の本文がJSONでなくてもステータスで判定する
  }
  return { status: response.status(), body };
}

async function pulledModels(): Promise<string[]> {
  const response = await fetch(`${STUB_URLS.llm}/__control/state`);
  expect(response.ok, 'LLMスタブの状態を取得できません').toBe(true);
  return ((await response.json()) as { pullRequests?: string[] }).pullRequests ?? [];
}

interface JobListItem {
  id: number;
  type: string;
  status: string;
}
interface JobDetail extends JobListItem {
  requestPayload: string | null;
}

/** 指定モデルのpullジョブ(新しい順で最初の1件)を探す。 */
async function findPullJob(request: APIRequestContext, projectId: number, model: string): Promise<JobDetail | null> {
  const headers = await adminHeaders(request);
  const list = await request.get('/api/generation-jobs', { headers });
  expect(list.ok(), `ジョブ一覧の取得に失敗しました (status=${list.status()})`).toBe(true);
  for (const item of (await list.json()) as JobListItem[]) {
    if (item.type !== 'ollama_model_pull') continue;
    const detail = await request.get(`/api/generation-jobs/${item.id}`, { headers });
    const job = (await detail.json()) as JobDetail;
    const payload = JSON.parse(job.requestPayload ?? '{}') as { model?: string; projectId?: number };
    if (payload.model === model && payload.projectId === projectId) return job;
  }
  return null;
}

Given('プロジェクトのメンバーである一般利用者がいる', async ({ ctx, request }) => {
  const me = await request.get('/api/identity/me', { headers: await memberHeaders(request) });
  expect(me.ok(), `一般利用者の情報を取得できませんでした (status=${me.status()})`).toBe(true);
  const userId = ((await me.json()) as { id: number }).id;
  const added = await request.post(`/api/projects/${ctx.connectionPanelProjectId}/users`, {
    headers: await adminHeaders(request),
    data: { userId, wpRole: 'editor' },
  });
  expect(added.ok(), `メンバー追加に失敗しました (status=${added.status()}): ${await added.text()}`).toBe(true);
});

When(/^Ollamaのモデル名欄に「(.*)」を入力してインストールを押す$/, async ({ page }, model: string) => {
  await page.getByLabel(INPUT_LABEL).fill(model);
  await section(page).getByRole('button', { name: 'インストール' }).click();
});

When(/^一般利用者がモデル「(.*)」のpullを開始しようとする$/, async ({ ctx, request }, model: string) => {
  ctx.ollamaPull = await pullViaApi(request, ctx.connectionPanelProjectId as number, await memberHeaders(request), model);
});

When(/^管理者がモデル「(.*)」のpullを開始しようとする$/, async ({ ctx, request }, model: string) => {
  const response = await pullViaApi(request, ctx.connectionPanelProjectId as number, await adminHeaders(request), model);
  ctx.ollamaPullPrevious = ctx.ollamaPull;
  ctx.ollamaPull = response;
});

Then('画面にインストールの進捗が百分率つきで表示される', async ({ page }) => {
  await expect(section(page).getByText(/downloading \d+%/)).toBeVisible({ timeout: 30_000 });
});

Then(/^Ollamaのスタブが「(.*)」のpullを受け取っている$/, async ({}, model: string) => {
  await expect.poll(pulledModels, { timeout: 15_000 }).toContain(model);
});

Then(/^Ollamaのスタブが「(.*)」のpullを受け取っていない$/, async ({}, model: string) => {
  expect(await pulledModels()).not.toContain(model);
});

Then(/^画面に「(.*)」のインストール完了が表示される$/, async ({ page }, model: string) => {
  await expect(section(page).getByText(`「${model}」のインストールが完了しました。`)).toBeVisible({ timeout: 60_000 });
});

Then(/^画面にインストールの失敗の理由として「(.*)」が表示される$/, async ({ page }, reason: string) => {
  await expect(section(page).getByRole('alert').filter({ hasText: 'インストールに失敗しました' })).toContainText(reason, {
    timeout: 60_000,
  });
});

Then(/^モデル「(.*)」のpullジョブは(done|failed)で終わっている$/, async ({ ctx, request }, model: string, status: string) => {
  const projectId = ctx.connectionPanelProjectId as number;
  await expect
    .poll(async () => (await findPullJob(request, projectId, model))?.status, { timeout: 30_000 })
    .toBe(status);
});

Then('pullの開始は403で拒否される', async ({ ctx }) => {
  expect((ctx.ollamaPull as PullResponse).status).toBe(403);
});

Then('pullの開始は400で拒否される', async ({ ctx }) => {
  expect((ctx.ollamaPull as PullResponse).status).toBe(400);
});

Then('pullの開始は受け付けられ、新しいジョブが作られる', async ({ ctx }) => {
  const response = ctx.ollamaPull as PullResponse;
  expect(response.status).toBe(200);
  expect(response.body.alreadyRunning).toBe(false);
  expect(typeof response.body.jobId).toBe('number');
});

Then('pullの開始は受け付けられ、実行中の同じジョブが返る', async ({ ctx }) => {
  const response = ctx.ollamaPull as PullResponse;
  const previous = ctx.ollamaPullPrevious as PullResponse;
  expect(response.status).toBe(200);
  expect(response.body.alreadyRunning).toBe(true);
  expect(response.body.jobId).toBe(previous.body.jobId);
});
