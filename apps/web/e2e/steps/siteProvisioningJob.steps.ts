import type { APIRequestContext } from '@playwright/test';
import { After, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * WordPressサイト自動構築のジョブとしての非同期受理(issue #1479)のステップ定義。
 * `site-provisioning-job.feature` が使う。同期API(`POST /api/sites/managed-wordpress`)は
 * `siteProvisioning.steps.ts` / `publishAuthor.steps.ts` 側で扱い、ここでは触れない。
 */

/** 受理応答の上限。構築(最大240秒)を待たずに返ることが要件(AC1)。 */
const ACCEPT_BUDGET_MS = 3000;
/** 構築(実測で最大240秒)の完了を待つ上限。 */
const JOB_TIMEOUT_MS = 280_000;

interface JobAccept {
  status: number;
  body: string;
  elapsedMs: number;
  jobId: number | null;
  jobStatus: string | null;
}

interface JobFinal {
  status: string;
  resultPayload: string | null;
}

interface JobState {
  siteKey: string;
  accept: JobAccept | null;
  final: JobFinal | null;
}

/** issue #765と同じ理由(並列実行時の衝突・孤児サイト対策)でユニークな名前を作る。 */
function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

function state(ctx: Record<string, unknown>): JobState {
  const value = ctx.siteJob as JobState | undefined;
  expect(value, 'サイト自動構築がまだ要求されていません').toBeDefined();
  return value as JobState;
}

function provisionBody(siteKey: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    name: `E2E job ${siteKey}`,
    siteKey,
    title: `E2E job ${siteKey}`,
    adminUser: `e2ejob${siteKey.replace(/[^a-zA-Z0-9]/g, '')}`.slice(0, 30),
    adminEmail: `e2e-${siteKey}@letsblog.local`,
    adminPassword: 'E2eProvisionJob#Passw0rd1',
    ...overrides,
  };
}

async function requestJob(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  siteKey: string,
  body: Record<string, unknown>
): Promise<void> {
  const token = await adminToken(request);
  const startedAt = Date.now();
  const response = await request.post('/api/sites/managed-wordpress/jobs', {
    headers: { Authorization: `Bearer ${token}` },
    data: body,
    timeout: 30_000,
  });
  const elapsedMs = Date.now() - startedAt;
  const text = await response.text();
  let parsed: { id?: number; status?: string } = {};
  try {
    parsed = JSON.parse(text) as { id?: number; status?: string };
  } catch {
    // 本文がJSONでなければ後続の検証がステータスと本文を出して失敗する。
  }
  const previous = ctx.siteJob as JobState | undefined;
  ctx.siteJob = {
    siteKey,
    accept: {
      status: response.status(),
      body: text,
      elapsedMs,
      jobId: parsed.id ?? null,
      jobStatus: parsed.status ?? null,
    },
    final: null,
  } satisfies JobState;
  if (previous === undefined) {
    ctx.siteJobFirstKey = siteKey;
  }
}

When('サイト自動構築をジョブとして要求する', async ({ ctx, request }) => {
  const siteKey = `e2ejob-${uniqueSuffix()}`;
  await requestJob(request, ctx, siteKey, provisionBody(siteKey));
});

When('同じsiteKeyでサイト自動構築をもう一度ジョブとして要求する', async ({ ctx, request }) => {
  const siteKey = ctx.siteJobFirstKey as string;
  expect(siteKey, '先にサイト自動構築を要求していません').toBeTruthy();
  await requestJob(request, ctx, siteKey, provisionBody(siteKey));
});

When('管理者パスワードの無いサイト自動構築をジョブとして要求する', async ({ ctx, request }) => {
  const siteKey = `e2ejob-${uniqueSuffix()}`;
  const body = provisionBody(siteKey);
  delete body.adminPassword;
  await requestJob(request, ctx, siteKey, body);
});

Then(
  /^サイト自動構築のジョブIDが「(\w+)」の状態で3秒以内に返る$/,
  async ({ ctx }, status: string) => {
    const { accept } = state(ctx);
    expect(accept, '要求していません').not.toBeNull();
    expect(accept?.status, `応答本文: ${accept?.body}`).toBe(202);
    expect(accept?.jobId, `ジョブIDが返っていません: ${accept?.body}`).not.toBeNull();
    expect(accept?.jobStatus).toBe(status);
    expect(accept?.elapsedMs, '構築の完了を待たずに返るはず').toBeLessThan(ACCEPT_BUDGET_MS);
  }
);

Then(/^サイト自動構築の要求は「(\d+)」で断られる$/, async ({ ctx }, status: string) => {
  const { accept } = state(ctx);
  expect(accept?.status, `応答本文: ${accept?.body}`).toBe(Number(status));
  expect(accept?.jobId, 'ジョブが作られてはいけません').toBeNull();
});

When('サイト自動構築のジョブが終わるまで待つ', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + JOB_TIMEOUT_MS);
  const job = state(ctx);
  expect(job.accept?.jobId, `ジョブIDが返っていません: ${job.accept?.body}`).not.toBeNull();
  const token = await adminToken(request);
  const phases = new Set<string>((ctx.siteJobPhases as string[] | undefined) ?? []);
  const deadline = Date.now() + JOB_TIMEOUT_MS;
  let last: JobFinal = { status: 'running', resultPayload: null };
  while (Date.now() < deadline) {
    const response = await request.get(`/api/generation-jobs/${job.accept?.jobId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(
      response.ok(),
      `ジョブの取得に失敗しました (status=${response.status()}): ${await response.text()}`
    ).toBe(true);
    last = (await response.json()) as JobFinal;
    if (last.status === 'running' && last.resultPayload) {
      // 実行中は進行段階(provisioning / registering)が引ける。
      const phase = (JSON.parse(last.resultPayload) as { phase?: string }).phase;
      if (phase) {
        phases.add(phase);
      }
    }
    if (last.status !== 'running') {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  ctx.siteJobPhases = [...phases];
  job.final = last;
});

Then(
  /^そのジョブは「done」で終わり、結果に作成されたサイトのIDが示される$/,
  async ({ ctx }) => {
    const { final } = state(ctx);
    expect(final, 'ジョブの終了を待っていません').not.toBeNull();
    expect(final?.status, `結果: ${final?.resultPayload}`).toBe('done');
    const result = JSON.parse(final?.resultPayload ?? '{}') as { siteId?: number; phase?: string };
    expect(typeof result.siteId, `結果にサイトIDがありません: ${final?.resultPayload}`).toBe('number');
    expect(result.phase).toBe('done');
    // 実行中に観測できた段階は、定義した順序の部分集合に限る(完了が速ければ観測できないこともある)。
    for (const phase of (ctx.siteJobPhases as string[] | undefined) ?? []) {
      expect(['provisioning', 'registering']).toContain(phase);
    }
    ctx.siteJobSiteId = result.siteId;
  }
);

Then('結果が示すサイトはサイト一覧に現れ、疎通確認が成功する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const list = await request.get('/api/sites', { headers });
  expect(list.ok(), `サイト一覧の取得に失敗しました (status=${list.status()})`).toBe(true);
  const sites = (await list.json()) as { id: number; siteKey: string }[];
  const siteId = ctx.siteJobSiteId as number;
  const found = sites.find((site) => site.id === siteId);
  expect(found, `サイト一覧に id=${siteId} が現れていません`).toBeTruthy();
  expect(found?.siteKey).toBe(ctx.siteJobFirstKey);
  const check = await request.post(`/api/sites/${siteId}/test-connection`, { headers, timeout: 60_000 });
  expect(check.ok(), `疎通確認に失敗しました (status=${check.status()})`).toBe(true);
  expect(((await check.json()) as { connectionCheckStatus: string }).connectionCheckStatus).toBe('SUCCESS');
});

/** JWTの sub クレーム(署名は検証しない。自分で取ったトークンの読み出しだけに使う)。 */
function subOf(token: string): string {
  const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8')) as { sub: string };
  return payload.sub;
}

Then(
  /^その構築の監査ログ「(\w+)」に操作者が残っている$/,
  async ({ ctx, request }, action: string) => {
    const token = await adminToken(request);
    const siteId = ctx.siteJobSiteId as number;
    // 監査ログは非同期(キュー)で書かれるため、現れるまで待つ。
    const deadline = Date.now() + 30_000;
    let entry: { userId: number | null; actorKeycloakSub: string | null } | undefined;
    while (Date.now() < deadline && !entry) {
      const response = await request.get('/api/audit-logs?page=0&size=100&sort=createdAt,desc', {
        headers: { Authorization: `Bearer ${token}` },
      });
      expect(response.ok(), `監査ログの取得に失敗しました (status=${response.status()})`).toBe(true);
      const content = ((await response.json()) as {
        content: { action: string; resourceId: number | null; userId: number | null; actorKeycloakSub: string | null }[];
      }).content;
      entry = content.find((item) => item.action === action && item.resourceId === siteId);
      if (!entry) {
        await new Promise((resolve) => setTimeout(resolve, 1000));
      }
    }
    expect(entry, `${action} の監査ログが site #${siteId} に現れていません`).toBeTruthy();
    expect(entry?.actorKeycloakSub).toBe(subOf(token));
    expect(entry?.userId, '操作者のユーザーIDが残っていません').not.toBeNull();
  }
);

Then(
  /^そのジョブは「failed」で終わり、理由に重複したsiteKeyが示される$/,
  async ({ ctx }) => {
    const { final } = state(ctx);
    expect(final, 'ジョブの終了を待っていません').not.toBeNull();
    expect(final?.status, `結果: ${final?.resultPayload}`).toBe('failed');
    const result = JSON.parse(final?.resultPayload ?? '{}') as { error?: string; errorType?: string };
    expect(result.errorType).toBe('duplicate_site_key');
    expect(result.error).toContain(ctx.siteJobFirstKey as string);
  }
);

/** 構築したサイトを(WordPress側の実体ごと)消す。失敗したシナリオでも孤児を残さない。 */
After({ tags: '@site-provisioning-job' }, async ({ ctx, request }) => {
  let siteId = ctx.siteJobSiteId as number | undefined;
  if (siteId === undefined) {
    // 結果の検証より前に落ちたシナリオでも、done の結果が示すサイトは消す。
    const payload = (ctx.siteJob as JobState | undefined)?.final?.resultPayload;
    siteId = payload ? (JSON.parse(payload) as { siteId?: number }).siteId : undefined;
  }
  if (siteId === undefined) {
    return;
  }
  const token = await adminToken(request);
  await request.delete(`/api/sites/${siteId}`, {
    headers: { Authorization: `Bearer ${token}` },
    timeout: 120_000,
  });
});
