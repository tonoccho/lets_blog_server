import Link from "next/link";
import { listOperationLogs } from "@/lib/apiClient";
import { requireSession, getViewerTimeZone } from "@/lib/session";
import { groupOperationLogEntries } from "./operationGroups";
import { OperationGroupRow } from "./OperationGroupRow";

const PAGE_SIZE = 50;

export default async function OperationLogsPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string }>;
}) {
  const session = await requireSession();
  const params = await searchParams;
  const page = Number(params.page ?? "0") || 0;
  const actor = { id: Number(session.user.id), role: session.user.role };
  const timezone = await getViewerTimeZone();

  const result = await listOperationLogs({ page, size: PAGE_SIZE }, actor).catch(() => ({
    content: [],
    totalElements: 0,
    totalPages: 0,
    number: 0,
    size: PAGE_SIZE,
  }));

  const groups = groupOperationLogEntries(result.content);

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-xl font-semibold">操作ログ</h1>
        <p className="mt-1 text-sm text-neutral-600 dark:text-neutral-400">
          このアカウントがサーバーと通信した操作の履歴です。トラブル発生時は「コピー」でトレースを取得し、AIやサポート担当者に共有してください。
        </p>
      </div>

      <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
        {groups.length === 0 && (
          <p className="px-4 py-6 text-center text-sm text-neutral-600 dark:text-neutral-400">
            記録された操作ログはありません
          </p>
        )}
        {groups.map((group) => (
          <OperationGroupRow key={group.operationId} group={group} timezone={timezone} />
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
                href={`/operation-logs?page=${p}`}
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
