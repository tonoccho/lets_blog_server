"use server";

import { listUnifiedOperationLogs, type UnifiedLogEntry } from "@/lib/apiClient";
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
