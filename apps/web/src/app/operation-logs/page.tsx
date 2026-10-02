import Link from "next/link";
import { listUnifiedOperationLogs, type UnifiedLogSourceType } from "@/lib/apiClient";
import { requireSession, getViewerTimeZone } from "@/lib/session";
import { localDateTimeToUtcIso } from "@/lib/formatDate";
import { UnifiedLogRow } from "./UnifiedLogRow";
import { OperationLogTimeZoneLabel } from "./OperationLogTimeZoneLabel";
import { BrowserTimeZoneField } from "./BrowserTimeZoneField";

const PAGE_SIZE = 50;

const TYPE_LABEL: Record<UnifiedLogSourceType, string> = {
  OPERATION: "操作",
  AI_JOB: "AI",
  AUDIT: "監査",
};

/** IANA TZ名として解釈できる値だけ返す。空・不正な値は undefined(サーバー既定TZへフォールバック)。 */
function validTimeZone(value: string | undefined): string | undefined {
  if (!value) return undefined;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value });
    return value;
  } catch {
    return undefined;
  }
}

export default async function OperationLogsPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string; type?: string; q?: string; startDate?: string; endDate?: string; tz?: string }>;
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
  // 個人設定TZが無いときは、フォームが送ったブラウザTZで解釈する(issue #1437)。
  const browserTimeZone = timezone === null ? validTimeZone(params.tz) : undefined;
  const filterTimeZone = timezone ?? browserTimeZone;
  const isAdmin = session.user.role === "admin";

  const result = await listUnifiedOperationLogs({
    type,
    q,
    startDate: localDateTimeToUtcIso(startDate, filterTimeZone),
    endDate: localDateTimeToUtcIso(endDate, filterTimeZone, { endOfMinute: true }),
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
    if (browserTimeZone) query.set("tz", browserTimeZone);
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
        <OperationLogTimeZoneLabel personalTimeZone={timezone} />
        {isAdmin && (
          <p className="mt-2 text-sm">
            <Link href="/operation-logs/slow" className="text-blue-700 underline dark:text-blue-300">
              遅い操作
            </Link>
            <span className="ml-2 text-neutral-600 dark:text-neutral-400">
              ルート別・操作別の所要時間を遅い順に一覧します(管理者のみ)。
            </span>
          </p>
        )}
      </div>

      <form className="flex flex-wrap items-end gap-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5" method="get">
        <BrowserTimeZoneField personalTimeZone={timezone} />
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
