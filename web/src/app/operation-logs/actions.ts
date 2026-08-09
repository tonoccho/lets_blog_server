"use server";

import { getOperationTrace } from "@/lib/apiClient";
import { requireSession, getViewerTimeZone } from "@/lib/session";
import { groupOperationLogEntries, describeOperationTraceText } from "./operationGroups";

/**
 * コピー時は表示中のページに載っている行だけでなく、operationIdに紐づく全呼び出しを
 * 取り直してからテキスト化する(1つの操作がページ境界をまたいでも欠落しないようにするため)。
 */
export async function copyOperationTraceAction(operationId: string): Promise<string> {
  const session = await requireSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const timezone = await getViewerTimeZone();

  const entries = await getOperationTrace(operationId, actor);
  const [group] = groupOperationLogEntries(entries);
  if (!group) {
    return `操作ID: ${operationId}\n(記録が見つかりませんでした)`;
  }
  return describeOperationTraceText(group, timezone);
}
