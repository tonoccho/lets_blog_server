import type { ArticleReviewPullRequest } from "@/lib/apiClient";
import { ViewerDateTime } from "@/components/ViewerDateTime";

/** レビュー待ちの Pull Request を表で並べる(issue #1340)。日時は閲覧者のタイムゾーンで表示する。 */
export function ArticleReviewPullRequestList({
  pullRequests,
  timezone,
}: {
  pullRequests: ArticleReviewPullRequest[];
  timezone: string | null;
}) {
  if (pullRequests.length === 0) {
    return (
      <p className="text-sm text-neutral-600 dark:text-neutral-400">レビュー待ちの Pull Request はありません</p>
    );
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 text-neutral-500 dark:text-neutral-400">
          <tr>
            <th className="px-4 py-2 font-medium">番号</th>
            <th className="px-4 py-2 font-medium">タイトル</th>
            <th className="px-4 py-2 font-medium">ブランチ</th>
            <th className="px-4 py-2 font-medium">作成日時</th>
            <th className="px-4 py-2 font-medium">リンク</th>
          </tr>
        </thead>
        <tbody>
          {pullRequests.map((pr) => (
            <tr key={pr.number} className="border-b border-neutral-100 dark:border-neutral-800 last:border-b-0">
              <td className="px-4 py-2 whitespace-nowrap">#{pr.number}</td>
              <td className="px-4 py-2">{pr.title}</td>
              <td className="px-4 py-2 font-mono text-xs">{pr.headBranch}</td>
              <td className="px-4 py-2 whitespace-nowrap">
                <ViewerDateTime iso={pr.createdAt} personalTimeZone={timezone} />
              </td>
              <td className="px-4 py-2 whitespace-nowrap">
                <a
                  href={pr.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-blue-600 dark:text-blue-400 hover:underline"
                >
                  GitHub で開く
                </a>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
