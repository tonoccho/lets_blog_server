import Link from "next/link";
import { listUnifiedOperationLogs, type UnifiedLogSourceType } from "@/lib/apiClient";
import { requireSession, getViewerTimeZone } from "@/lib/session";
import { localDateTimeToUtcIso } from "@/lib/formatDate";
import { UnifiedLogRow } from "./UnifiedLogRow";

const PAGE_SIZE = 50;

const TYPE_LABEL: Record<UnifiedLogSourceType, string> = {
  OPERATION: "操作",
  AI_JOB: "AI",
  AUDIT: "監査",
};

export default async function OperationLogsPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string; type?: string; q?: string; startDate?: string; endDate?: string }>;
}) {
  const session = await requireSession();
  const params = await searchParams;
  const page = Number(params.page ?? "0") || 0;
  const type = (params.type || undefined) as UnifiedLogSourceType | undefined;
  const q = params.q || undefined;
  // 日時は閲覧者TZの壁時計(datetime-local)で受け、APIへはUTCのISO日時で渡す(issue #1138)。
  const startDate = params.startDate || undefined;
  const endDate = params.endDate || undefined;
  const timezone = await getViewerTimeZone();
  const isAdmin = session.user.role === "admin";

  const result = await listUnifiedOperationLogs({
    type,
    q,
    startDate: localDateTimeToUtcIso(startDate, timezone),
    endDate: localDateTimeToUtcIso(endDate, timezone, { endOfMinute: true }),
    page,
    size: PAGE_SIZE,
  }).catch(() => ({
    content: [],
    totalElements: 0,
    totalPages: 0,
    number: 0,
    size: PAGE_SIZE,
  }));

  const buildQuery = (targetPage: number) => {
    const query = new URLSearchParams();
    if (type) query.set("type", type);
    if (q) query.set("q", q);
    if (startDate) query.set("startDate", startDate);
    if (endDate) query.set("endDate", endDate);
    query.set("page", String(targetPage));
    return query.toString();
  };

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-xl font-semibold">操作ログ</h1>
        <p className="mt-1 text-sm text-neutral-600 dark:text-neutral-400">
          操作(このアカウントのAPI呼び出し)・AIジョブ・
          {isAdmin ? "監査ログ" : ""}
          を時系列で一覧表示します。操作の行は「コピー」でトレースを取得し、AIやサポート担当者に共有できます。
        </p>
      </div>

      <form className="flex flex-wrap items-end gap-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5" method="get">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">種別</span>
          <select
            name="type"
            defaultValue={type ?? ""}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          >
            <option value="">すべて</option>
            {(Object.keys(TYPE_LABEL) as UnifiedLogSourceType[])
              .filter((t) => t !== "AUDIT" || isAdmin)
              .map((t) => (
                <option key={t} value={t}>
                  {TYPE_LABEL[t]}
                </option>
              ))}
          </select>
        </label>
        <label className="flex flex-1 flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">キーワード</span>
          <input
            name="q"
            type="text"
            defaultValue={q ?? ""}
            placeholder="パス・操作種別などで検索"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">開始日時</span>
          <input
            name="startDate"
            type="datetime-local"
            defaultValue={startDate ?? ""}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">終了日時</span>
          <input
            name="endDate"
            type="datetime-local"
            defaultValue={endDate ?? ""}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <button type="submit" className="rounded bg-neutral-900 px-4 py-2 text-sm text-white">
          絞り込み
        </button>
      </form>

      <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
        {result.content.length === 0 && (
          <p className="px-4 py-6 text-center text-sm text-neutral-600 dark:text-neutral-400">
            該当するログはありません
          </p>
        )}
        {result.content.map((entry) => (
          <UnifiedLogRow key={`${entry.sourceType}-${entry.id}`} entry={entry} timezone={timezone} />
        ))}
      </div>

      {result.totalPages > 1 && (
        <div className="flex items-center justify-between text-sm text-neutral-600 dark:text-neutral-400">
          <span>
            ページ {result.number + 1} / {result.totalPages}
          </span>
          <div className="flex gap-2">
            {Array.from({ length: result.totalPages }, (_, i) => i).map((p) => (
              <Link
                key={p}
                href={`/operation-logs?${buildQuery(p)}`}
                className={`rounded px-3 py-1 text-sm ${
                  p === page
                    ? "bg-neutral-900 text-white"
                    : "border border-neutral-300 dark:border-neutral-700 text-neutral-600 dark:text-neutral-400 hover:bg-neutral-50 dark:hover:bg-neutral-800"
                }`}
              >
                {p + 1}
              </Link>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
