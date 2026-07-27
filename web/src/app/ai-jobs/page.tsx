import { listGenerationJobs } from "@/lib/apiClient";

export default async function AiJobsPage() {
  const jobs = await listGenerationJobs().catch(() => []);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">AIジョブ</h1>
      <p className="text-sm text-neutral-500">
        Ollama(下書き・校正・要約・タグ提案)/ ComfyUI(画像生成)の実行履歴です。
      </p>

      <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
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
                <td colSpan={4} className="px-4 py-6 text-center text-neutral-400">
                  ジョブ履歴はありません
                </td>
              </tr>
            )}
            {jobs.map((job) => (
              <tr key={job.id} className="border-b border-neutral-100 last:border-0">
                <td className="px-4 py-2 font-mono">{job.type}</td>
                <td className="px-4 py-2">
                  <span className="rounded-full bg-neutral-100 px-2 py-0.5 text-xs">{job.status}</span>
                </td>
                <td className="px-4 py-2 text-neutral-500">{new Date(job.createdAt).toLocaleString("ja-JP")}</td>
                <td className="px-4 py-2 text-neutral-500">{new Date(job.updatedAt).toLocaleString("ja-JP")}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
