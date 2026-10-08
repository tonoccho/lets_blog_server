"use server";

import { getGeneratedImage, getGenerationJob, listGenerationJobs, listUnifiedOperationLogs, type UnifiedLogEntry } from "@/lib/apiClient";
import {
  CUSTOM_TAG_GENERATION_JOB_TYPE,
  GARBAGE_COLLECTION_JOB_TYPE,
  IMAGE_GENERATION_JOB_TYPE,
  STATIC_CONTENT_GENERATION_JOB_TYPE,
  TAG_DESIGN_GENERATION_JOB_TYPE,
  QUEUE_JOB_LIMIT,
  SITE_PROVISIONING_JOB_TYPE,
  buildImageGenerationResultHref,
  buildSiteProvisioningResultHref,
  readFailureReason,
  readImageIds,
  readSiteId,
  resolveResultHref,
  type QueueJob,
} from "./infoRailQueue";
import { getViewerTimeZone, requireSession } from "@/lib/session";

/**
 * 「結果を見る」の遷移先をリクエスト内容(project ID / site ID)から決める種別。詳細を取りに行く。
 * LLM生成(#1409)は、その機能の画面が結果を `jobId` で引くので、遷移先にジョブIDも要る。
 */
const NEEDS_REQUEST_PAYLOAD = new Set([
  GARBAGE_COLLECTION_JOB_TYPE,
  CUSTOM_TAG_GENERATION_JOB_TYPE,
  STATIC_CONTENT_GENERATION_JOB_TYPE,
  TAG_DESIGN_GENERATION_JOB_TYPE,
]);

/** 狭いレールに収まる件数。専用ページの PAGE_SIZE(50)は使わない。 */
const INFO_RAIL_LOG_COUNT = 10;

/**
 * 情報表示レールの「操作ログ」タブ用に、直近の操作ログを新しい順で取得する。
 * フィルタは持たない(絞り込みは /operation-logs の役割)。
 */
export async function fetchRecentOperationLogsAction(): Promise<{
  entries: UnifiedLogEntry[];
  timeZone: string | null;
}> {
  await requireSession();
  const [page, timeZone] = await Promise.all([
    listUnifiedOperationLogs({ page: 0, size: INFO_RAIL_LOG_COUNT }),
    getViewerTimeZone(),
  ]);
  return { entries: page.content, timeZone };
}

/**
 * 情報表示レールの「処理キュー」タブ用に、直近のジョブを新しい順で取得する。
 * 所有者による絞り込みは API 側(#1406)。完了ジョブにだけ「結果を見る」の遷移先を付ける。
 * 遷移先にリクエスト内容(project ID・site ID)が要る種別だけ、詳細を取りに行く。詳細が読めなければリンクなし。
 * 失敗したジョブは詳細の `resultPayload.error` を失敗理由として載せる(#1571)。
 * サイト自動構築は、完了したジョブの結果が示す作成済みサイトの編集画面を遷移先にする(#1696)。
 * 画像生成はリクエストに project ID が無いので、結果の最初の画像が属するプロジェクトを遷移先にする(#1408)。
 */
export async function fetchQueueJobsAction(): Promise<{ jobs: QueueJob[]; timeZone: string | null }> {
  await requireSession();
  const [all, timeZone] = await Promise.all([listGenerationJobs(), getViewerTimeZone()]);
  const recent = [...all]
    .sort((a, b) => (a.createdAt === b.createdAt ? b.id - a.id : a.createdAt < b.createdAt ? 1 : -1))
    .slice(0, QUEUE_JOB_LIMIT);
  const jobs = await Promise.all(
    recent.map(async (job): Promise<QueueJob> => {
      const base = { id: job.id, type: job.type, status: job.status, createdAt: job.createdAt, failureReason: null };
      if (job.status === "failed") {
        const detail = await getGenerationJob(job.id).catch(() => null);
        return { ...base, resultHref: null, failureReason: readFailureReason(detail?.resultPayload ?? null) };
      }
      if (job.status !== "done") return { ...base, resultHref: null };
      if (job.type === IMAGE_GENERATION_JOB_TYPE) return { ...base, resultHref: await imageGenerationHref(job.id) };
      if (job.type === SITE_PROVISIONING_JOB_TYPE) return { ...base, resultHref: await siteProvisioningHref(job.id) };
      if (!NEEDS_REQUEST_PAYLOAD.has(job.type)) return { ...base, resultHref: resolveResultHref(job.type, null) };
      const detail = await getGenerationJob(job.id).catch(() => null);
      return { ...base, resultHref: resolveResultHref(job.type, detail?.requestPayload ?? null, job.id) };
    }),
  );
  return { jobs, timeZone };
}

/** 結果に作成されたサイトのIDが無い・詳細が取得できないときは null。サイトはリクエストの時点では無いので、結果から導く(#1696)。 */
async function siteProvisioningHref(jobId: number): Promise<string | null> {
  const detail = await getGenerationJob(jobId).catch(() => null);
  const siteId = readSiteId(detail?.resultPayload ?? null);
  return siteId === null ? null : buildSiteProvisioningResultHref(siteId);
}

/** 画像が1枚も無い・所属プロジェクトが無い・取得できない(削除済みなど)ときは null。 */
async function imageGenerationHref(jobId: number): Promise<string | null> {
  try {
    const detail = await getGenerationJob(jobId);
    const [firstImageId] = readImageIds(detail.resultPayload);
    if (firstImageId === undefined) return null;
    const image = await getGeneratedImage(firstImageId);
    return image.projectId == null ? null : buildImageGenerationResultHref(image.projectId, jobId);
  } catch {
    return null;
  }
}
