"use server";

import { getOperationTrace } from "@/lib/apiClient";
import { requireSession } from "@/lib/session";
import { groupOperationLogEntries, describeOperationTraceText } from "./operationGroups";

/**
 * コピー時は表示中のページに載っている行だけでなく、operationIdに紐づく全呼び出しを
 * 取り直してからテキスト化する(1つの操作がページ境界をまたいでも欠落しないようにするため)。
 */
export async function copyOperationTraceAction(operationId: string): Promise<string> {
  await requireSession();

  const entries = await getOperationTrace(operationId);
  const [group] = groupOperationLogEntries(entries);
  if (!group) {
    return `操作ID: ${operationId}\n(記録が見つかりませんでした)`;
  }
  return describeOperationTraceText(group);
}
