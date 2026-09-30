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
 * プロジェクト単位のOllama / ComfyUI接続先の上書き(issue #1503)のステップ定義。
 * 対象API: `GET` / `PUT /api/projects/{id}/ai-models/connections`。
 *
 * 他のai系ステップ定義ファイルと同様、ヘルパーはこのファイル内に閉じて持つ。
 */

type ScenarioState = Record<string, unknown>;

interface Entry {
  overrideBaseUrl?: string | null;
  baseUrl?: string | null;
  source?: string;
}
interface Connections {
  ollama: Entry;
  comfyui: Entry;
}

async function bearer(request: APIRequestContext, email: string, password: string) {
  return { Authorization: `Bearer ${await fetchAccessToken(request, email, password)}` };
}
const adminHeaders = (request: APIRequestContext) => bearer(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
const memberHeaders = (request: APIRequestContext) => bearer(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

function projectIds(ctx: ScenarioState): number[] {
  ctx.conn1503Projects ??= [];
  return ctx.conn1503Projects as number[];
}

async function createProject(request: APIRequestContext, ctx: ScenarioState): Promise<number> {
  const suffix = uniqueSuffix();
  const created = await request.post('/api/projects', {
    headers: await adminHeaders(request),
    data: { name: `E2E 1503 ${suffix}`, slug: `e2e-1503-${suffix}` },
  });
  expect(created.ok(), `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`).toBe(true);
  const id = ((await created.json()) as { id: number }).id;
  projectIds(ctx).push(id);
  return id;
}

const url = (projectId: number) => `/api/projects/${projectId}/ai-models/connections`;
const first = (ctx: ScenarioState) => projectIds(ctx)[0];
const second = (ctx: ScenarioState) => projectIds(ctx)[1];

async function readConnections(request: APIRequestContext, projectId: number): Promise<Connections> {
  const response = await request.get(url(projectId), { headers: await adminHeaders(request) });
  expect(response.status(), `接続先の取得に失敗しました: ${await response.text()}`).toBe(200);
  return (await response.json()) as Connections;
}

async function adminPut(request: APIRequestContext, projectId: number, data: Record<string, string>) {
  return request.put(url(projectId), { headers: await adminHeaders(request), data });
}

Given('接続先の上書きを確かめるプロジェクトが2つあり、一般利用者は片方だけのメンバーである', async ({ ctx, request }) => {
  const memberProject = await createProject(request, ctx);
  await createProject(request, ctx);

  const me = await request.get('/api/identity/me', { headers: await memberHeaders(request) });
  expect(me.ok(), `一般利用者の情報を取得できませんでした (status=${me.status()})`).toBe(true);
  const memberUserId = ((await me.json()) as { id: number }).id;
  const added = await request.post(`/api/projects/${memberProject}/users`, {
    headers: await adminHeaders(request),
    data: { userId: memberUserId, wpRole: 'editor' },
  });
  expect(added.ok(), `メンバー追加に失敗しました (status=${added.status()}): ${await added.text()}`).toBe(true);
});

Given(
  /^管理者が1つ目のプロジェクトのOllama接続先を「(.+)」に設定してある$/,
  async ({ ctx, request }, value: string) => {
    const response = await adminPut(request, first(ctx), { ollamaBaseUrl: value });
    expect(response.status(), await response.text()).toBe(200);
  }
);

When(
  /^管理者が1つ目のプロジェクトのOllama接続先を「(.+)」に、ComfyUI接続先を「(.+)」に設定する$/,
  async ({ ctx, request }, ollama: string, comfy: string) => {
    const response = await adminPut(request, first(ctx), { ollamaBaseUrl: ollama, comfyuiBaseUrl: comfy });
    expect(response.status(), await response.text()).toBe(200);
  }
);

When('管理者が1つ目のプロジェクトのOllama接続先を空文字で保存する', async ({ ctx, request }) => {
  const response = await adminPut(request, first(ctx), { ollamaBaseUrl: '' });
  expect(response.status(), await response.text()).toBe(200);
});

When(
  /^管理者が1つ目のプロジェクトのOllama接続先を「(.+)」に保存しようとする$/,
  async ({ ctx, request }, value: string) => {
    ctx.conn1503Status = (await adminPut(request, first(ctx), { ollamaBaseUrl: value })).status();
  }
);

When(
  /^管理者が1つ目のプロジェクトのComfyUI接続先を「(.+)」に保存しようとする$/,
  async ({ ctx, request }, value: string) => {
    ctx.conn1503Status = (await adminPut(request, first(ctx), { comfyuiBaseUrl: value })).status();
  }
);

When('一般利用者が2つ目のプロジェクトの接続先を参照しようとする', async ({ ctx, request }) => {
  ctx.conn1503Status = (await request.get(url(second(ctx)), { headers: await memberHeaders(request) })).status();
});

When(
  /^一般利用者が2つ目のプロジェクトのOllama接続先を「(.+)」に更新しようとする$/,
  async ({ ctx, request }, value: string) => {
    ctx.conn1503Status = (
      await request.put(url(second(ctx)), {
        headers: await memberHeaders(request),
        data: { ollamaBaseUrl: value },
      })
    ).status();
  }
);

Then('1つ目のプロジェクトのOllamaとComfyUIの解決結果が設定したURLで出所がPROJECTである', async ({ ctx, request }) => {
  const c = await readConnections(request, first(ctx));
  expect(c.ollama.baseUrl).toBe('http://at-1503-ollama.invalid:11434/v1');
  expect(c.ollama.source).toBe('PROJECT');
  expect(c.comfyui.baseUrl).toBe('http://at-1503-comfy.invalid:8188');
  expect(c.comfyui.source).toBe('PROJECT');
});

Then('2つ目のプロジェクトのOllamaとComfyUIの解決結果は上書きを持たず出所がPROJECTではない', async ({ ctx, request }) => {
  const c = await readConnections(request, second(ctx));
  for (const entry of [c.ollama, c.comfyui]) {
    expect(entry.overrideBaseUrl ?? null).toBeNull();
    expect(entry.source).not.toBe('PROJECT');
  }
});

Then('1つ目のプロジェクトのOllamaの解決結果は上書きを持たず出所がPROJECTではない', async ({ ctx, request }) => {
  const c = await readConnections(request, first(ctx));
  expect(c.ollama.overrideBaseUrl ?? null).toBeNull();
  expect(c.ollama.source).not.toBe('PROJECT');
});

Then('2つ目のプロジェクトのOllamaの解決結果は上書きを持たず出所がPROJECTではない', async ({ ctx, request }) => {
  const c = await readConnections(request, second(ctx));
  expect(c.ollama.overrideBaseUrl ?? null).toBeNull();
  expect(c.ollama.source).not.toBe('PROJECT');
});

Then('保存は400で拒否される', async ({ ctx }) => {
  expect(ctx.conn1503Status).toBe(400);
});

Then('接続先の操作は403で拒否される', async ({ ctx }) => {
  expect(ctx.conn1503Status).toBe(403);
});

Then(
  /^1つ目のプロジェクトのOllama接続先の上書きは「(.+)」のままである$/,
  async ({ ctx, request }, value: string) => {
    const c = await readConnections(request, first(ctx));
    expect(c.ollama.overrideBaseUrl).toBe(value);
    expect(c.comfyui.overrideBaseUrl ?? null).toBeNull();
  }
);

After({ tags: '@ai' }, async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  for (const id of projectIds(ctx)) {
    await request.delete(`/api/projects/${id}`, { headers });
  }
});
