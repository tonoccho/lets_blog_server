import Link from "next/link";
import {
  getOperationStats,
  getOperationTrace,
  getRouteStats,
  type OperationLogEntry,
  type OperationStat,
  type RouteStat,
} from "@/lib/apiClient";
import { requireAdminSession, getViewerTimeZone } from "@/lib/session";
import { formatOperationLogDateTime, localDateTimeToUtcIso } from "@/lib/formatDate";
import { BrowserTimeZoneField } from "../BrowserTimeZoneField";
import { OperationLogTimeZoneLabel } from "../OperationLogTimeZoneLabel";
import {
  buildSlowQuery,
  defaultSlowPeriod,
  nextDirection,
  OPERATION_SORTS,
  ROUTE_SORTS,
  type SortDirection,
} from "./slowStats";

interface SearchParams {
  startDate?: string;
  endDate?: string;
  tz?: string;
  routeSort?: string;
  routeDir?: string;
  opSort?: string;
  opDir?: string;
  trace?: string;
}

const ROUTE_COLUMNS: { key: (typeof ROUTE_SORTS)[number]; label: string }[] = [
  { key: "count", label: "件数" },
  { key: "p50", label: "p50(ms)" },
  { key: "p95", label: "p95(ms)" },
  { key: "max", label: "最大(ms)" },
];

const OPERATION_COLUMNS: { key: (typeof OPERATION_SORTS)[number]; label: string }[] = [
  { key: "totalDuration", label: "合計所要時間(ms)" },
  { key: "callCount", label: "呼び出し数" },
  { key: "startedAt", label: "開始時刻" },
];

function validTimeZone(value: string | undefined): string | undefined {
  if (!value) return undefined;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value });
    return value;
  } catch {
    return undefined;
  }
}

function pick<T extends string>(value: string | undefined, allowed: readonly T[], fallback: T): T {
  return allowed.includes(value as T) ? (value as T) : fallback;
}

function direction(value: string | undefined): SortDirection {
  return value === "asc" ? "asc" : "desc";
}

const TH = "px-3 py-2 text-left font-medium";
const TD = "px-3 py-2";

export default async function SlowOperationsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  await requireAdminSession();
  const params = await searchParams;

  const routeSort = pick(params.routeSort, ROUTE_SORTS, "p95");
  const routeDir = direction(params.routeDir ?? "desc");
  const opSort = pick(params.opSort, OPERATION_SORTS, "totalDuration");
  const opDir = direction(params.opDir ?? "desc");

  const timezone = await getViewerTimeZone();
  const browserTimeZone = timezone === null ? validTimeZone(params.tz) : undefined;
  const filterTimeZone = timezone ?? browserTimeZone;
  const fallbackPeriod = defaultSlowPeriod(new Date());
  const startDate = localDateTimeToUtcIso(params.startDate, filterTimeZone) ?? fallbackPeriod.startDate;
  const endDate = localDateTimeToUtcIso(params.endDate, filterTimeZone, { endOfMinute: true }) ?? fallbackPeriod.endDate;

  const [routes, operations] = await Promise.all([
    getRouteStats({ startDate, endDate, sort: routeSort, direction: routeDir }).catch(() => null),
    getOperationStats({ startDate, endDate, sort: opSort, direction: opDir }).catch(() => null),
  ]);
  const failed = routes === null || operations === null;

  const traceId = params.trace || undefined;
  const trace: OperationLogEntry[] | null = traceId
    ? await getOperationTrace(traceId).catch(() => [])
    : null;

  const state = {
    startDate: params.startDate,
    endDate: params.endDate,
    tz: browserTimeZone,
    routeSort: params.routeSort,
    routeDir: params.routeDir,
    opSort: params.opSort,
    opDir: params.opDir,
    trace: traceId,
  };
  const href = (overrides: Record<string, string | undefined>) =>
    `/operation-logs/slow?${buildSlowQuery({ ...state, ...overrides })}`;
  const displayTimeZone = timezone ?? browserTimeZone ?? undefined;
  const indicator = (active: boolean, dir: SortDirection) => (active ? (dir === "desc" ? " ▼" : " ▲") : "");

  const routeRows: RouteStat[] = routes ?? [];
  const operationRows: OperationStat[] = operations ?? [];

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-xl font-semibold">遅い操作</h1>
        <p className="mt-1 text-sm text-neutral-600 dark:text-neutral-400">
          記録済みの操作ログの所要時間を、期間を指定して集計します(管理者のみ)。ルートの数値ID・UUIDは{"{id}"}、
          クエリ文字列は除いて集約します。列見出しで並べ替えられ、操作の行から全利用者の操作トレースを表示できます。
        </p>
        <OperationLogTimeZoneLabel personalTimeZone={timezone} />
        <p className="mt-1 text-sm">
          <Link href="/operation-logs" className="text-blue-700 underline dark:text-blue-300">
            操作ログへ戻る
          </Link>
        </p>
      </div>

      <form
        className="flex flex-wrap items-end gap-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5"
        method="get"
      >
        <BrowserTimeZoneField personalTimeZone={timezone} />
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">開始日時</span>
          <input
            name="startDate"
            type="datetime-local"
            defaultValue={params.startDate ?? ""}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">終了日時</span>
          <input
            name="endDate"
            type="datetime-local"
            defaultValue={params.endDate ?? ""}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <button type="submit" className="rounded bg-neutral-900 px-4 py-2 text-sm text-white">
          集計する
        </button>
        <span className="text-xs text-neutral-500">未指定の場合は直近24時間です。</span>
      </form>

      {failed && (
        <p role="alert" className="rounded border border-red-300 bg-red-50 px-4 py-3 text-sm text-red-700">
          集計を取得できませんでした。時間をおいて再度お試しください。
        </p>
      )}

      <section className="space-y-2">
        <h2 className="text-lg font-semibold">ルート別</h2>
        <div className="overflow-x-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
          <table className="w-full text-sm" data-testid="route-stats-table">
            <thead>
              <tr className="border-b border-neutral-200 dark:border-neutral-800">
                <th className={TH}>ルート</th>
                {ROUTE_COLUMNS.map((c) => (
                  <th key={c.key} className={TH}>
                    <Link
                      href={href({ routeSort: c.key, routeDir: nextDirection(routeSort, routeDir, c.key) })}
                      className="underline"
                    >
                      {c.label}
                      {indicator(c.key === routeSort, routeDir)}
                    </Link>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {routeRows.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-3 py-6 text-center text-neutral-600">
                    該当する記録はありません
                  </td>
                </tr>
              )}
              {routeRows.map((r) => (
                <tr key={`${r.method} ${r.path}`} className="border-b border-neutral-100 dark:border-neutral-800">
                  <td className={TD}>
                    {r.method} {r.path}
                  </td>
                  <td className={TD}>{r.count}</td>
                  <td className={TD}>{r.p50Ms}</td>
                  <td className={TD}>{r.p95Ms}</td>
                  <td className={TD}>{r.maxMs}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="space-y-2">
        <h2 className="text-lg font-semibold">操作別</h2>
        <div className="overflow-x-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
          <table className="w-full text-sm" data-testid="operation-stats-table">
            <thead>
              <tr className="border-b border-neutral-200 dark:border-neutral-800">
                <th className={TH}>操作ID</th>
                <th className={TH}>利用者ID</th>
                {OPERATION_COLUMNS.map((c) => (
                  <th key={c.key} className={TH}>
                    <Link
                      href={href({ opSort: c.key, opDir: nextDirection(opSort, opDir, c.key) })}
                      className="underline"
                    >
                      {c.label}
                      {indicator(c.key === opSort, opDir)}
                    </Link>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {operationRows.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-3 py-6 text-center text-neutral-600">
                    該当する記録はありません
                  </td>
                </tr>
              )}
              {operationRows.map((o) => (
                <tr key={o.operationId} className="border-b border-neutral-100 dark:border-neutral-800">
                  <td className={TD}>
                    <Link href={href({ trace: o.operationId })} className="font-mono text-xs underline">
                      {o.operationId}
                    </Link>
                  </td>
                  <td className={TD}>{o.userId ?? "-"}</td>
                  <td className={TD}>{o.totalDurationMs}</td>
                  <td className={TD}>{o.callCount}</td>
                  <td className={TD}>{formatOperationLogDateTime(o.startedAt, displayTimeZone)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {traceId && trace && (
        <section className="space-y-2" data-testid="operation-trace">
          <h2 className="text-lg font-semibold">操作トレース: {traceId}</h2>
          {trace.length === 0 ? (
            <p className="text-sm text-neutral-600">記録が見つかりませんでした</p>
          ) : (
            <div className="overflow-x-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-neutral-200 dark:border-neutral-800">
                    <th className={TH}>日時</th>
                    <th className={TH}>呼び出し</th>
                    <th className={TH}>ステータス</th>
                    <th className={TH}>所要時間(ms)</th>
                    <th className={TH}>結果</th>
                  </tr>
                </thead>
                <tbody>
                  {trace.map((e) => (
                    <tr key={e.id} className="border-b border-neutral-100 dark:border-neutral-800">
                      <td className={TD}>{formatOperationLogDateTime(e.createdAt, displayTimeZone)}</td>
                      <td className={TD}>
                        {e.method} {e.path}
                      </td>
                      <td className={TD}>{e.statusCode ?? "(応答なし)"}</td>
                      <td className={TD}>{e.durationMs}</td>
                      <td className={TD}>
                        {e.success ? "成功" : "失敗"}
                        {e.errorMessage ? `: ${e.errorMessage}` : ""}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </div>
  );
}
