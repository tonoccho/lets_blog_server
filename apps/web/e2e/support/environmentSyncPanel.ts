import type { APIRequestContext, Locator, Page } from '@playwright/test';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from './index';
import { clickUntilVisible } from './retryClick';

/**
 * 「環境同期」パネル(`EnvironmentSyncPanel.tsx`)の操作と、同期ジョブ(`environment_sync`、issue #1697)の
 * 取得の共通部品。`environment-sync.feature`(#1075 / #1680)と `environment-sync-web-job.feature`(#1697)の
 * ステップ定義が使う。ステップは登録しない。
 *
 * 同期は #1697 でジョブとして受理されるだけになり、画面は完了を待たない。同期先の実状態を調べる
 * シナリオは、受理されたジョブの完了を {@link waitForSyncJobDone} で待ってから進む。
 */

export const SYNC_JOB_TYPE = 'environment_sync';
export const SYNC_ACCEPTED_NOTICE = /同期を要求しました。処理キューに追加されました/;
const JOB_LOOKUP_TIMEOUT_MS = 30_000;
const JOB_DONE_TIMEOUT_MS = 240_000;

interface JobDetail {
  id: number;
  type: string;
  status: string;
  requestPayload: string | null;
  resultPayload: string | null;
}

async function authHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)}` };
}

/** プロジェクトの管理画面の「設定」タブを開き、環境同期パネルを返す。 */
export async function openSyncPanel(page: Page, projectId: number): Promise<Locator> {
  await page.goto(`/projects/${projectId}`);
  const panel = page
    .locator('div.rounded-lg', { has: page.getByRole('heading', { name: '環境同期' }) })
    .first();
  // issue #1386: goto直後はハイドレーション未完了で「設定」タブのクリックが空振りしうる。タブは
  // 活性タブの内容だけを描画するので、環境同期パネルの出現を期待値に、べき等なタブ切り替えを
  // 再試行する。「同期する」は非べき等なので再試行せず、パネル(クライアント描画)の出現後に1回だけ押す。
  await clickUntilVisible(page.locator('button:has-text("設定")'), panel);
  return panel;
}

/** テスト環境→ローカルで、対象を1つ選ぶ。 */
export async function fillSyncPanel(
  panel: Locator,
  target: 'themes' | 'plugins' | 'media' | 'db' = 'db'
): Promise<void> {
  await panel.locator('select[name="from"]').selectOption('test');
  await panel.locator('select[name="to"]').selectOption('local');
  await panel.locator(`input[name="targets"][value="${target}"]`).check();
}

/** 確認ダイアログを受け入れて「同期する」を1回だけ押す。 */
export async function submitSyncPanel(page: Page, panel: Locator): Promise<void> {
  page.once('dialog', (dialog) => dialog.accept());
  await panel.getByRole('button', { name: '同期する', exact: true }).click();
}

function payloadProjectId(requestPayload: string | null): number | null {
  try {
    const value = (JSON.parse(requestPayload ?? 'null') as { projectId?: unknown } | null)?.projectId;
    return typeof value === 'number' ? value : null;
  } catch {
    return null;
  }
}

/** ジョブ一覧(一覧は id と type だけの要約)から、環境間同期のジョブの id を返す。詳細は取らない。 */
async function listSyncJobIds(request: APIRequestContext): Promise<number[]> {
  const headers = await authHeaders(request);
  const list = await request.get('/api/generation-jobs', { headers });
  expect(list.ok(), `ジョブ一覧の取得に失敗しました (status=${list.status()})`).toBe(true);
  return ((await list.json()) as { id: number; type: string }[]).filter((j) => j.type === SYNC_JOB_TYPE).map((j) => j.id);
}

/**
 * `afterJobId` より新しい、そのプロジェクトの環境間同期ジョブ。詳細(`/api/generation-jobs/{id}`)は
 * `afterJobId` より新しいものだけ取る。
 *
 * #1766: かつては履歴にある環境間同期ジョブを全件、1秒おきに詳細取得していた。ジョブは実行のたびに溜まり
 * (数百件)、1回の探索で数百リクエストになって gateway の `API_RATE_LIMIT_REQUESTS`(1000回/分)を使い切り、
 * 同じワーカーのフィクスチャ作成・削除や他ワーカーが 429 で落ちた。詳細を取るのは新しいジョブだけにして、
 * リクエスト数を履歴の長さに依存させない。
 */
async function listSyncJobsAfter(request: APIRequestContext, projectId: number, afterJobId: number): Promise<JobDetail[]> {
  const headers = await authHeaders(request);
  const jobs: JobDetail[] = [];
  for (const id of (await listSyncJobIds(request)).filter((jobId) => jobId > afterJobId)) {
    const detail = await request.get(`/api/generation-jobs/${id}`, { headers });
    if (!detail.ok()) continue;
    const body = (await detail.json()) as JobDetail;
    // ジョブの要求内容は JSON 列に保存され、読み出すと `"projectId": 7` のように空白が入る。JSON として読んで比べる。
    if (payloadProjectId(body.requestPayload) === projectId) jobs.push(body);
  }
  return jobs;
}

/**
 * 環境間同期ジョブのうち最新のid(どのプロジェクトのものでもよい)。無ければ 0。操作の前後でどれが新しいかを
 * 見分けるのに使う。基準が「全プロジェクトの最新」でも、`findSyncJob` は projectId でも絞るので結果は変わらない。
 */
export async function latestSyncJobId(request: APIRequestContext): Promise<number> {
  return (await listSyncJobIds(request)).reduce((max, id) => Math.max(max, id), 0);
}

/** `afterJobId` より新しい、そのプロジェクトの環境間同期ジョブを探す。受理後に作られるので短く再試行する。 */
export async function findSyncJob(request: APIRequestContext, projectId: number, afterJobId: number): Promise<JobDetail> {
  const deadline = Date.now() + JOB_LOOKUP_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const job = (await listSyncJobsAfter(request, projectId, afterJobId))[0];
    if (job) return job;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`プロジェクト ${projectId} の環境間同期ジョブが見つかりません`);
}

/** ジョブが done になるまで待つ。failed なら理由つきで失敗させる。 */
export async function waitForSyncJobDone(request: APIRequestContext, jobId: number): Promise<JobDetail> {
  const headers = await authHeaders(request);
  const deadline = Date.now() + JOB_DONE_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const response = await request.get(`/api/generation-jobs/${jobId}`, { headers });
    if (response.ok()) {
      const job = (await response.json()) as JobDetail;
      if (job.status === 'done') return job;
      if (job.status === 'failed') throw new Error(`環境間同期ジョブ ${jobId} が失敗しました: ${job.resultPayload}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error(`環境間同期ジョブ ${jobId} が ${JOB_DONE_TIMEOUT_MS}ms 以内に完了しません`);
}
