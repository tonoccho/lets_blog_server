import { listGenerationJobs } from "@/lib/apiClient";
import { formatDateTime } from "@/lib/formatDate";
import { getViewerTimeZone } from "@/lib/session";

export default async function AiJobsPage() {
  const [jobs, timezone] = await Promise.all([listGenerationJobs().catch(() => []), getViewerTimeZone()]);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">AIジョブ</h1>
      <p className="text-sm text-neutral-500 dark:text-neutral-400">
        Ollama(下書き・校正・要約・タグ提案)/ ComfyUI(画像生成)の実行履歴です。
      </p>

      <div className="text-sm text-neutral-600 dark:text-neutral-400">
        全{jobs.length}件を表示
      </div>

      <div className="overflow-x-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 text-neutral-500 dark:text-neutral-400">
            <tr>
              <th className="px-4 py-2">種別</th>
              <th className="px-4 py-2">ステータス</th>
              <th className="px-4 py-2">作成日時</th>
              <th className="px-4 py-2">更新日時</th>
            </tr>
          </thead>
          <tbody>
            {jobs.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-6 text-center text-neutral-600 dark:text-neutral-400">
                  ジョブ履歴はありません
                </td>
              </tr>
            )}
            {jobs.map((job) => (
              <tr key={job.id} className="border-b border-neutral-100 dark:border-neutral-800 last:border-0 cursor-pointer hover:bg-neutral-50 dark:hover:bg-neutral-800 hover:shadow-sm transition-colors">
                <td className="px-4 py-2 font-mono">{job.type}</td>
                <td className="px-4 py-2">
                  <span className="rounded-full bg-neutral-100 dark:bg-neutral-800 px-2 py-0.5 text-xs">{job.status}</span>
                </td>
                <td className="px-4 py-2 text-neutral-500 dark:text-neutral-400">{formatDateTime(job.createdAt, timezone)}</td>
                <td className="px-4 py-2 text-neutral-500 dark:text-neutral-400">{formatDateTime(job.updatedAt, timezone)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
