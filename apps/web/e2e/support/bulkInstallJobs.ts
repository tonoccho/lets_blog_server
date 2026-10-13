import type { APIRequestContext } from '@playwright/test';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from './index';

/**
 * 全環境インストールのジョブ(`bulk_slug_install` / `bulk_zip_install`、issue #1702)の取得の共通部品。
 * `environmentSyncPanel.ts`(#1697)の同種の部品を、ジョブ種別を引数にして持つ。ステップは登録しない。
 */

export type BulkInstallJobType = 'bulk_slug_install' | 'bulk_zip_install';

export const BULK_INSTALL_ACCEPTED_NOTICE = /全環境へのインストールを要求しました。処理キューに追加されました/;
const JOB_LOOKUP_TIMEOUT_MS = 30_000;
const JOB_DONE_TIMEOUT_MS = 240_000;

export interface BulkInstallJobDetail {
  id: number;
  type: string;
  status: string;
  requestPayload: string | null;
  resultPayload: string | null;
}

async function authHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)}` };
}

function payloadProjectId(requestPayload: string | null): number | null {
  try {
    const value = (JSON.parse(requestPayload ?? 'null') as { projectId?: unknown } | null)?.projectId;
    return typeof value === 'number' ? value : null;
  } catch {
    return null;
  }
}

async function listJobs(request: APIRequestContext, projectId: number, type: BulkInstallJobType): Promise<BulkInstallJobDetail[]> {
  const headers = await authHeaders(request);
  const list = await request.get('/api/generation-jobs', { headers });
  expect(list.ok(), `ジョブ一覧の取得に失敗しました (status=${list.status()})`).toBe(true);
  const jobs: BulkInstallJobDetail[] = [];
  for (const job of ((await list.json()) as { id: number; type: string }[]).filter((j) => j.type === type)) {
    const detail = await request.get(`/api/generation-jobs/${job.id}`, { headers });
    if (!detail.ok()) continue;
    const body = (await detail.json()) as BulkInstallJobDetail;
    // ジョブの要求内容は JSON 列に保存され、読み出すと `"projectId": 7` のように空白が入る。JSON として読んで比べる。
    if (payloadProjectId(body.requestPayload) === projectId) jobs.push(body);
  }
  return jobs;
}

/** そのプロジェクトの、その種別のジョブのうち最新のid。無ければ 0。操作の前後でどれが新しいかを見分けるのに使う。 */
export async function latestBulkInstallJobId(
  request: APIRequestContext,
  projectId: number,
  type: BulkInstallJobType
): Promise<number> {
  const jobs = await listJobs(request, projectId, type);
  return jobs.reduce((max, job) => Math.max(max, job.id), 0);
}

/** `afterJobId` より新しい、そのプロジェクトのその種別のジョブを探す。受理後に作られるので短く再試行する。 */
export async function findBulkInstallJob(
  request: APIRequestContext,
  projectId: number,
  type: BulkInstallJobType,
  afterJobId: number
): Promise<BulkInstallJobDetail> {
  const deadline = Date.now() + JOB_LOOKUP_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const job = (await listJobs(request, projectId, type)).find((j) => j.id > afterJobId);
    if (job) return job;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`プロジェクト ${projectId} の ${type} ジョブが見つかりません`);
}

/**
 * ジョブが done になるまで待つ。failed なら理由つきで失敗させる(環境ごとの失敗は logs の FAILED で、ジョブは done になる。
 * ジョブが failed になるのは操作全体の失敗だけ)。
 */
export async function waitForBulkInstallJobDone(request: APIRequestContext, jobId: number): Promise<BulkInstallJobDetail> {
  const headers = await authHeaders(request);
  const deadline = Date.now() + JOB_DONE_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const response = await request.get(`/api/generation-jobs/${jobId}`, { headers });
    if (response.ok()) {
      const job = (await response.json()) as BulkInstallJobDetail;
      if (job.status === 'done') return job;
      if (job.status === 'failed') throw new Error(`全環境インストールのジョブ ${jobId} が失敗しました: ${job.resultPayload}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error(`全環境インストールのジョブ ${jobId} が ${JOB_DONE_TIMEOUT_MS}ms 以内に完了しません`);
}
