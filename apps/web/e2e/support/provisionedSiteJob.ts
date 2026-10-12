import type { APIRequestContext, Page } from '@playwright/test';
import { E2E_ADMIN_EMAIL } from './index';
import { adminHeaders } from './e2eFixtures';
import { waitForProvisionedSiteRow, type ProvisionedJob } from './provisionedSiteRow';

/**
 * `GET /api/generation-jobs` から、そのサイトキーを要求に持つ `site_provisioning` ジョブを探す(issue #1768)。
 * 探し方は `siteProvisioningWebJob.steps.ts` の `findJob` と同じ(リクエスト内容のサイトキーで特定する)。
 * 失敗の理由は結果ペイロードの `error`。
 */
export function makeProvisionedJobFinder(
  request: APIRequestContext,
  headers: () => Promise<Record<string, string>>
): (siteKey: string) => Promise<ProvisionedJob | null> {
  return async (siteKey: string) => {
    const auth = await headers();
    const list = await request.get('/api/generation-jobs', { headers: auth });
    if (!list.ok()) throw new Error(`ジョブ一覧の取得に失敗 (status=${list.status()})`);
    const jobs = ((await list.json()) as { id: number; type: string }[]).filter((j) => j.type === 'site_provisioning');
    for (const job of jobs) {
      const detail = await request.get(`/api/generation-jobs/${job.id}`, { headers: auth });
      if (!detail.ok()) continue;
      const body = (await detail.json()) as {
        id: number;
        status: string;
        requestPayload: string | null;
        resultPayload: string | null;
      };
      if (!body.requestPayload?.includes(`"${siteKey}"`)) continue;
      return { id: body.id, status: body.status, error: parseError(body.resultPayload) };
    }
    return null;
  };
}

function parseError(resultPayload: string | null): string | null {
  if (!resultPayload) return null;
  try {
    const error = (JSON.parse(resultPayload) as { error?: unknown }).error;
    return typeof error === 'string' ? error : null;
  } catch {
    return resultPayload;
  }
}

/** 管理者として読んだ一覧に、そのサイトの行が現れるまで待つ。失敗の診断(ジョブの状態など)は `waitForProvisionedSiteRow` が付ける。 */
export async function waitForProvisionedSiteRowAsAdmin(page: Page, request: APIRequestContext, siteKey: string): Promise<void> {
  await waitForProvisionedSiteRow(page, siteKey, {
    readUser: E2E_ADMIN_EMAIL,
    findJob: makeProvisionedJobFinder(request, () => adminHeaders(request)),
  });
}
