export type SortDirection = "asc" | "desc";

export const ROUTE_SORTS = ["count", "p50", "p95", "max"] as const;
export const OPERATION_SORTS = ["totalDuration", "callCount", "startedAt"] as const;

/** 列見出しを選んだときの向き。いま並べている列なら反転、別の列なら降順から(issue #1471)。 */
export function nextDirection(activeSort: string, activeDir: SortDirection, column: string): SortDirection {
  if (column === activeSort) return activeDir === "desc" ? "asc" : "desc";
  return "desc";
}

/** 期間が未指定のときの既定: 現在から24時間前まで。UTCのISO日時(秒まで、Zなし)。 */
export function defaultSlowPeriod(now: Date): { startDate: string; endDate: string } {
  const iso = (d: Date) => d.toISOString().slice(0, 19);
  return { startDate: iso(new Date(now.getTime() - 24 * 60 * 60 * 1000)), endDate: iso(now) };
}

/** 値のあるものだけをクエリ文字列にする。 */
export function buildSlowQuery(params: Record<string, string | undefined>): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value) query.set(key, value);
  }
  return query.toString();
}
