import type { APIRequestContext } from '@playwright/test';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from './index';

/**
 * アセット画像の全環境アップロードのジョブ(`asset_image_upload`、issue #1764)の取得の共通部品。
 * `bulkInstallJobs.ts`(#1702)と同じ形。ステップは登録しない。
 */

export const ASSET_IMAGE_UPLOAD_JOB_TYPE = 'asset_image_upload';

/** 受理したときにパネルへ出る案内(`assetUploadJobResult.ts` の `ASSET_UPLOAD_ACCEPTED_MESSAGE`)。 */
export const ASSET_UPLOAD_ACCEPTED_NOTICE = /アセットとしての追加を要求しました。処理キューに追加されました/;

/** 結果として表示される文言(`describeAssetUploadJob`。成功も失敗も、環境ごとの成否の要約)。 */
export const ASSET_UPLOAD_RESULT_TEXT = /環境へアップロードしました。|環境でアップロードに失敗しました。/;

const JOB_LOOKUP_TIMEOUT_MS = 30_000;
const JOB_DONE_TIMEOUT_MS = 120_000;

export interface AssetUploadJobDetail {
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

async function listJobs(request: APIRequestContext, projectId: number): Promise<AssetUploadJobDetail[]> {
  const headers = await authHeaders(request);
  const list = await request.get('/api/generation-jobs', { headers });
  expect(list.ok(), `ジョブ一覧の取得に失敗しました (status=${list.status()})`).toBe(true);
  const jobs: AssetUploadJobDetail[] = [];
  for (const job of ((await list.json()) as { id: number; type: string }[]).filter((j) => j.type === ASSET_IMAGE_UPLOAD_JOB_TYPE)) {
    const detail = await request.get(`/api/generation-jobs/${job.id}`, { headers });
    if (!detail.ok()) continue;
    const body = (await detail.json()) as AssetUploadJobDetail;
    // ジョブの要求内容は JSON 列に保存され、読み出すと `"projectId": 7` のように空白が入る。JSON として読んで比べる。
    if (payloadProjectId(body.requestPayload) === projectId) jobs.push(body);
  }
  return jobs;
}

/** そのプロジェクトの `asset_image_upload` ジョブのうち最新のid。無ければ 0。操作の前後でどれが新しいかを見分けるのに使う。 */
export async function latestAssetUploadJobId(request: APIRequestContext, projectId: number): Promise<number> {
  const jobs = await listJobs(request, projectId);
  return jobs.reduce((max, job) => Math.max(max, job.id), 0);
}

/** `afterJobId` より新しい、そのプロジェクトの `asset_image_upload` ジョブを探す。受理後に作られるので短く再試行する。 */
export async function findAssetUploadJob(
  request: APIRequestContext,
  projectId: number,
  afterJobId: number
): Promise<AssetUploadJobDetail> {
  const deadline = Date.now() + JOB_LOOKUP_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const job = (await listJobs(request, projectId)).find((j) => j.id > afterJobId);
    if (job) return job;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`プロジェクト ${projectId} の ${ASSET_IMAGE_UPLOAD_JOB_TYPE} ジョブが見つかりません`);
}

/** ジョブが done になるまで待つ。failed なら理由つきで失敗させる(環境ごとの失敗は logs の FAILED で、ジョブは done)。 */
export async function waitForAssetUploadJobDone(request: APIRequestContext, jobId: number): Promise<AssetUploadJobDetail> {
  const headers = await authHeaders(request);
  const deadline = Date.now() + JOB_DONE_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const response = await request.get(`/api/generation-jobs/${jobId}`, { headers });
    if (response.ok()) {
      const job = (await response.json()) as AssetUploadJobDetail;
      if (job.status === 'done') return job;
      if (job.status === 'failed') throw new Error(`アセット追加のジョブ ${jobId} が失敗しました: ${job.resultPayload}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error(`アセット追加のジョブ ${jobId} が ${JOB_DONE_TIMEOUT_MS}ms 以内に完了しません`);
}
