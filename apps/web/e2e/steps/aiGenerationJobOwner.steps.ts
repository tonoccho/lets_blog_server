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
 * 生成ジョブの所有者による絞り込み(issue #1406)のステップ定義。
 *
 * ジョブは `POST /api/ai/draft` が作る(LLMはスタブ。`@stub`)。応答にジョブIDは含まれないため、
 * リクエスト本文に一意な印を入れ、管理者(全件を見られる)の一覧と詳細の `requestPayload` から
 * 自分が起こしたジョブを特定する。
 */

interface JobSummary {
  id: number;
  type: string;
}

interface JobDetail {
  id: number;
  requestPayload: string | null;
}

type Actor = 'user' | 'admin';

function tokenFor(request: APIRequestContext, actor: Actor): Promise<string> {
  return actor === 'user'
    ? fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD)
    : fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function listJobs(request: APIRequestContext, actor: Actor): Promise<JobSummary[]> {
  const response = await request.get('/api/generation-jobs', {
    headers: { Authorization: `Bearer ${await tokenFor(request, actor)}` },
  });
  expect(
    response.ok(),
    `生成ジョブ一覧の取得に失敗しました (actor=${actor}, status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as JobSummary[];
}

async function requestDraft(request: APIRequestContext, actor: Actor): Promise<string> {
  const marker = `owner-marker-${actor}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const response = await request.post('/api/ai/draft', {
    headers: { Authorization: `Bearer ${await tokenFor(request, actor)}` },
    data: { mode: 'draft', text: `所有者の確認 ${marker}` },
  });
  expect(
    response.ok(),
    `下書き生成に失敗しました (actor=${actor}, status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return marker;
}

/** 管理者は全件を見られるので、印を持つ下書きジョブをここから特定する。 */
export async function findJobIdByMarker(request: APIRequestContext, marker: string): Promise<number> {
  const adminJobs = (await listJobs(request, 'admin')).filter((job) => job.type === 'llm_draft');
  const token = await tokenFor(request, 'admin');
  for (const job of adminJobs.slice(0, 20)) {
    const detail = await request.get(`/api/generation-jobs/${job.id}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (detail.ok() && ((await detail.json()) as JobDetail).requestPayload?.includes(marker)) {
      return job.id;
    }
  }
  throw new Error(`印(${marker})を持つ llm_draft ジョブが管理者の一覧に見つかりません`);
}

When('一般利用者がAI下書き生成を依頼する', async ({ ctx, request }) => {
  ctx.ownerJobMarker = await requestDraft(request, 'user');
});

When('管理者がAI下書き生成を依頼する', async ({ ctx, request }) => {
  ctx.ownerJobMarker = await requestDraft(request, 'admin');
});

async function expectListed(
  request: APIRequestContext,
  actor: Actor,
  marker: string,
  listed: boolean
): Promise<void> {
  const jobId = await findJobIdByMarker(request, marker);
  const ids = (await listJobs(request, actor)).map((job) => job.id);
  expect(
    ids.includes(jobId),
    `${actor}の一覧に job=${jobId} が${listed ? '含まれるはず' : '含まれないはず'}です: ${JSON.stringify(ids)}`
  ).toBe(listed);
}

Then('一般利用者の生成ジョブ一覧にそのジョブが含まれる', async ({ ctx, request }) => {
  await expectListed(request, 'user', ctx.ownerJobMarker as string, true);
});

Then('管理者の生成ジョブ一覧にもそのジョブが含まれる', async ({ ctx, request }) => {
  await expectListed(request, 'admin', ctx.ownerJobMarker as string, true);
});

Then('管理者の生成ジョブ一覧にそのジョブが含まれる', async ({ ctx, request }) => {
  await expectListed(request, 'admin', ctx.ownerJobMarker as string, true);
});

Then('一般利用者の生成ジョブ一覧にそのジョブは含まれない', async ({ ctx, request }) => {
  await expectListed(request, 'user', ctx.ownerJobMarker as string, false);
});

Then('一般利用者がそのジョブをIDで照会すると404が返る', async ({ ctx, request }) => {
  const jobId = await findJobIdByMarker(request, ctx.ownerJobMarker as string);
  const response = await request.get(`/api/generation-jobs/${jobId}`, {
    headers: { Authorization: `Bearer ${await tokenFor(request, 'user')}` },
  });
  expect(response.status(), `他人のジョブの照会結果: ${await response.text()}`).toBe(404);
});

Then('管理者がそのジョブをIDで照会すると取得できる', async ({ ctx, request }) => {
  const jobId = await findJobIdByMarker(request, ctx.ownerJobMarker as string);
  const response = await request.get(`/api/generation-jobs/${jobId}`, {
    headers: { Authorization: `Bearer ${await tokenFor(request, 'admin')}` },
  });
  expect(response.status()).toBe(200);
});
