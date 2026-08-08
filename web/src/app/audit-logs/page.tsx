import Link from "next/link";
import { listAuditLogs } from "@/lib/apiClient";
import { requireAdminSession, getViewerTimeZone } from "@/lib/session";
import { formatDateTime } from "@/lib/formatDate";

const ACTIONS = [
  "LOGIN",
  "USER_CREATED",
  "USER_UPDATED",
  "USER_DELETED",
  "POST_PUBLISHED",
  "SITE_REGISTERED",
  "PASSWORD_RESET_REQUESTED",
  "PASSWORD_RESET_CONFIRMED",
];

export default async function AuditLogsPage({
  searchParams,
}: {
  searchParams: Promise<{ userId?: string; action?: string; page?: string }>;
}) {
  const session = await requireAdminSession();
  const params = await searchParams;
  const page = Number(params.page ?? "0") || 0;
  const actor = { id: Number(session.user.id), role: session.user.role };
  const timezone = await getViewerTimeZone();

  const result = await listAuditLogs(
    {
      userId: params.userId ? Number(params.userId) : undefined,
      action: params.action || undefined,
      page,
    },
    actor
  ).catch(() => ({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }));

  const buildQuery = (targetPage: number) => {
    const query = new URLSearchParams();
    if (params.userId) query.set("userId", params.userId);
    if (params.action) query.set("action", params.action);
    query.set("page", String(targetPage));
    return query.toString();
  };

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">監査ログ</h1>

      <form className="flex flex-wrap items-end gap-3 rounded-lg border border-neutral-200 bg-white p-5" method="get">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">ユーザーID</span>
          <input
            name="userId"
            type="number"
            defaultValue={params.userId ?? ""}
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">操作種別</span>
          <select
            name="action"
            defaultValue={params.action ?? ""}
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          >
            <option value="">すべて</option>
            {ACTIONS.map((action) => (
              <option key={action} value={action}>
                {action}
              </option>
            ))}
          </select>
        </label>
        <button type="submit" className="rounded bg-neutral-900 px-4 py-2 text-sm text-white">
          絞り込み
        </button>
      </form>

      <div className="mb-3 flex items-center justify-between text-sm text-neutral-600">
        <span>
          {result.content.length > 0
            ? `${result.number * result.size + 1}〜${Math.min((result.number + 1) * result.size, result.totalElements)}件 / 全${result.totalElements}件を表示`
            : `全${result.totalElements}件`}
        </span>
      </div>

      <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
            <tr>
              <th className="px-4 py-2">日時</th>
              <th className="px-4 py-2">ユーザーID</th>
              <th className="px-4 py-2">操作</th>
              <th className="px-4 py-2">リソース</th>
              <th className="px-4 py-2">IPアドレス</th>
            </tr>
          </thead>
          <tbody>
            {result.content.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-6 text-center text-neutral-600">
                  該当する監査ログはありません
                </td>
              </tr>
            )}
            {result.content.map((entry) => (
              <tr key={entry.id} className="border-b border-neutral-100 last:border-0 cursor-pointer hover:bg-neutral-50 hover:shadow-sm transition-colors">
                <td className="px-4 py-2 text-neutral-600">{formatDateTime(entry.createdAt, timezone)}</td>
                <td className="px-4 py-2 font-mono">{entry.userId ?? "-"}</td>
                <td className="px-4 py-2">{entry.action}</td>
                <td className="px-4 py-2 text-neutral-600">
                  {entry.resourceType ? `${entry.resourceType}${entry.resourceId ? ` #${entry.resourceId}` : ""}` : "-"}
                </td>
                <td className="px-4 py-2 text-neutral-600">{entry.remoteIp ?? "-"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {result.totalPages > 1 && (
        <div className="space-y-3">
          <div className="flex items-center justify-between text-sm text-neutral-600">
            <span>
              ページ {result.number + 1} / {result.totalPages}
            </span>
            <span>
              {result.content.length > 0
                ? `${result.number * result.size + 1}〜${Math.min((result.number + 1) * result.size, result.totalElements)}件 / 全${result.totalElements}件`
                : `全${result.totalElements}件`}
            </span>
          </div>
          <div className="flex gap-2">
            {Array.from({ length: result.totalPages }, (_, i) => i).map((p) => (
              <Link
                key={p}
                href={`/audit-logs?${buildQuery(p)}`}
                className={`rounded px-3 py-1 text-sm ${
                  p === page ? "bg-neutral-900 text-white" : "border border-neutral-300 text-neutral-600 hover:bg-neutral-50"
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
