import type { OperationLogEntry } from "@/lib/apiClient";
import { formatOperationLogDateTime } from "@/lib/formatDate";

export interface OperationGroup {
  operationId: string;
  entries: OperationLogEntry[];
  startedAt: string;
  success: boolean;
}

/**
 * 同一操作(1回のブラウザ操作で発生した複数のAPI呼び出し)をoperationIdでまとめる。
 * グループ内は発生順(古い順)、グループ同士は新しい順に並べる。
 */
export function groupOperationLogEntries(entries: OperationLogEntry[]): OperationGroup[] {
  const grouped = new Map<string, OperationLogEntry[]>();
  for (const entry of entries) {
    const list = grouped.get(entry.operationId) ?? [];
    list.push(entry);
    grouped.set(entry.operationId, list);
  }

  return Array.from(grouped.entries())
    .map(([operationId, list]) => {
      const sorted = [...list].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
      return {
        operationId,
        entries: sorted,
        startedAt: sorted[0].createdAt,
        success: sorted.every((entry) => entry.success),
      };
    })
    .sort((a, b) => b.startedAt.localeCompare(a.startedAt));
}

/** AIやサポート担当者との共有を想定した、操作の完全なトレースをテキスト化する。 */
export function describeOperationTraceText(group: OperationGroup, timezone: string | null): string {
  const lines = [
    `操作ID: ${group.operationId}`,
    `開始日時: ${formatOperationLogDateTime(group.startedAt, timezone)}`,
    `呼び出し件数: ${group.entries.length}`,
    `結果: ${group.success ? "成功" : "エラーあり"}`,
    "",
    "呼び出し一覧:",
  ];
  group.entries.forEach((entry, index) => {
    lines.push(`${index + 1}. [${formatOperationLogDateTime(entry.createdAt, timezone)}] ${entry.method} ${entry.path}`);
    lines.push(
      `   ステータス: ${entry.statusCode ?? "(応答なし)"} / ${entry.durationMs}ms / ${entry.success ? "成功" : "失敗"}`
    );
    if (entry.errorMessage) {
      lines.push(`   エラー: ${entry.errorMessage}`);
    }
  });
  return lines.join("\n");
}
