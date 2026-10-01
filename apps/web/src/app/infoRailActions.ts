"use server";

import { getGenerationJob, listGenerationJobs, listUnifiedOperationLogs, type UnifiedLogEntry } from "@/lib/apiClient";
import {
  GARBAGE_COLLECTION_JOB_TYPE,
  QUEUE_JOB_LIMIT,
  resolveResultHref,
  type QueueJob,
} from "./infoRailQueue";
import { getViewerTimeZone, requireSession } from "@/lib/session";

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
 * 遷移先にリクエスト内容(project ID)が要る種別だけ、詳細を取りに行く。詳細が読めなければリンクなし。
 */
export async function fetchQueueJobsAction(): Promise<{ jobs: QueueJob[]; timeZone: string | null }> {
  await requireSession();
  const [all, timeZone] = await Promise.all([listGenerationJobs(), getViewerTimeZone()]);
  const recent = [...all]
    .sort((a, b) => (a.createdAt === b.createdAt ? b.id - a.id : a.createdAt < b.createdAt ? 1 : -1))
    .slice(0, QUEUE_JOB_LIMIT);
  const jobs = await Promise.all(
    recent.map(async (job): Promise<QueueJob> => {
      const base = { id: job.id, type: job.type, status: job.status, createdAt: job.createdAt };
      if (job.status !== "done") return { ...base, resultHref: null };
      if (job.type !== GARBAGE_COLLECTION_JOB_TYPE) return { ...base, resultHref: resolveResultHref(job.type, null) };
      const detail = await getGenerationJob(job.id).catch(() => null);
      return { ...base, resultHref: resolveResultHref(job.type, detail?.requestPayload ?? null) };
    }),
  );
  return { jobs, timeZone };
}
