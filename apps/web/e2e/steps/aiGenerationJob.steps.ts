import type { APIRequestContext } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 生成ジョブの状態照会(issue #1151 / AT-8-6、issue #934のシナリオ12・13)のステップ定義。
 *
 * `mediaGarbageCollection.steps.ts` と別ファイルにしているのは issue #1151 のスコープ
 * 指定によるほか、こちらは削除の成否そのものではなく `GET /api/generation-jobs` 系の
 * 状態表現だけを見るため、実メディアの用意(wp-cliでの画像インポート)を必要としない
 * (フィーチャーファイルの「なぜ存在しないメディアIDで足りるか」を参照)。
 *
 * プロジェクト・WordPressサイトの用意はこのファイル専用のスラッグで独立に行う。
 * `mediaGarbageCollection.steps.ts` のフィクスチャを共有すると、削除する実メディアの
 * 有無によって挙動が変わりうる別フィーチャーへ、この機能の前提を暗黙に結合してしまう。
 */

const PROJECT_SLUG = 'e2e-at8-6-genjob';
const SITE_KEY = 'at86genjob';
const PROVISION_TIMEOUT_MS = 600_000;
const JOB_TIMEOUT_MS = 300_000;

interface SiteFixture {
  id: number;
  siteKey: string;
}

interface GenerationJobDetail {
  id: number;
  type: string;
  status: string;
  resultPayload: string | null;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function ensureProject(request: APIRequestContext): Promise<number> {
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const list = await request.get('/api/projects', { headers });
  expect(
    list.ok(),
    `プロジェクト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`
  ).toBe(true);
  const existing = ((await list.json()) as { id: number; slug: string }[]).find(
    (project) => project.slug === PROJECT_SLUG
  );
  if (existing) {
    return existing.id;
  }
  const created = await request.post('/api/projects', {
    headers,
    data: { name: 'E2E AT8-6 Generation Job', slug: PROJECT_SLUG },
  });
  expect(
    created.ok(),
    `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  return ((await created.json()) as { id: number }).id;
}

async function ensureManagedSite(request: APIRequestContext): Promise<SiteFixture> {
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const list = await request.get('/api/sites', { headers });
  expect(
    list.ok(),
    `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`
  ).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find((site) => site.siteKey === SITE_KEY);
  if (existing) {
    return existing;
  }
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: 'AT8-6 generation job probe site',
      siteKey: SITE_KEY,
      title: 'AT8-6 GenJob Probe',
      adminUser: 'at86genjobadmin',
      adminEmail: 'at8-6-genjob-probe@letsblog.local',
      adminPassword: 'At86GenJobProbe#Passw0rd1',
    },
    timeout: PROVISION_TIMEOUT_MS,
  });
  expect(
    created.ok(),
    `マネージドWordPressの構築に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  return (await created.json()) as SiteFixture;
}

async function bindLocalEnvironment(
  request: APIRequestContext,
  projectId: number,
  siteId: number
): Promise<void> {
  const token = await adminToken(request);
  const response = await request.post(`/api/projects/${projectId}/environments`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { environment: 'local', siteId },
  });
  expect(
    response.ok(),
    `local環境へのサイト紐付けに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

async function fetchJob(request: APIRequestContext, jobId: number): Promise<GenerationJobDetail> {
  const token = await adminToken(request);
  const response = await request.get(`/api/generation-jobs/${jobId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.ok(),
    `ジョブの取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as GenerationJobDetail;
}

async function waitForTerminal(request: APIRequestContext, jobId: number): Promise<GenerationJobDetail> {
  const deadline = Date.now() + JOB_TIMEOUT_MS;
  let last = await fetchJob(request, jobId);
  while (Date.now() < deadline && (last.status === 'running' || last.status === 'pending')) {
    await new Promise((resolve) => setTimeout(resolve, 1000));
    last = await fetchJob(request, jobId);
  }
  return last;
}

Given('生成ジョブの状態照会を確かめるためのWordPressサイトを持つプロジェクトがある', async ({ ctx, request }) => {
  const projectId = await ensureProject(request);
  const site = await ensureManagedSite(request);
  await bindLocalEnvironment(request, projectId, site.id);
  ctx.genJobProjectId = projectId;
});

When('存在しないメディアIDを指定してガベージコレクションの削除を起動する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const nonExistentMediaId = `999999999${Date.now()}`;
  const response = await request.post(
    `/api/projects/${ctx.genJobProjectId}/media-garbage-collection/delete?environment=local`,
    {
      headers: { Authorization: `Bearer ${token}` },
      data: { mediaIds: [nonExistentMediaId] },
    }
  );
  expect(
    response.ok(),
    `ガベージコレクションの開始に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.genJobId = ((await response.json()) as { id: number }).id;
});

Then('起動直後のジョブをIDで照会すると実行中の状態が返る', async ({ ctx, request }) => {
  const job = await fetchJob(request, ctx.genJobId as number);
  expect(job.status, `起動直後のジョブが実行中(running)ではありません: ${JSON.stringify(job)}`).toBe(
    'running'
  );
});

Then('生成ジョブの一覧にそのジョブが含まれ、状態が返る', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const response = await request.get('/api/generation-jobs', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.ok(),
    `生成ジョブ一覧の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const jobs = (await response.json()) as { id: number; status: string }[];
  const found = jobs.find((job) => job.id === ctx.genJobId);
  expect(found, `一覧に起動したジョブ(id=${ctx.genJobId})が含まれていません`).toBeTruthy();
  expect(found?.status, `一覧上のジョブに状態が含まれていません: ${JSON.stringify(found)}`).toBeTruthy();
});

Then('そのジョブは最終的に失敗の状態になる', async ({ ctx, request }) => {
  const job = await waitForTerminal(request, ctx.genJobId as number);
  ctx.genJobFinal = job;
  expect(job.status, `ジョブが失敗(failed)で終わりませんでした: ${JSON.stringify(job)}`).toBe('failed');
});

Then('失敗の理由が結果に含まれる', async ({ ctx }) => {
  const job = ctx.genJobFinal as GenerationJobDetail;
  expect(job.resultPayload, '失敗したジョブの結果に理由が含まれていません').toBeTruthy();
  const payload = JSON.parse(job.resultPayload as string) as { failures?: Record<string, string> };
  expect(payload.failures, `失敗理由(failures)が結果に含まれていません: ${job.resultPayload}`).toBeTruthy();
  expect(
    Object.values(payload.failures ?? {}),
    `失敗理由の中身が空です: ${job.resultPayload}`
  ).not.toHaveLength(0);
});
