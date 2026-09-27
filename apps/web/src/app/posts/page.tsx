import { listPosts } from "@/lib/apiClient";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { requireSession, getViewerTimeZone } from "@/lib/session";
import { ViewerDateTime } from "@/components/ViewerDateTime";
import { PostsTable } from "./PostsTable";

export default async function PostsPage() {
  // セッションが更新不能なときはここで /login へリダイレクトする(issue #1234)。
  // 以前はセッションの状態を見ずに描画しており、失敗したlistPosts()を
  // catch(() => [])で握り潰すため「投稿履歴が0件」に見えていた。
  await requireSession();
  const [postsResult, timezone] = await Promise.all([
    loadOrReport("posts", "投稿履歴", listPosts(), []),
    getViewerTimeZone(),
  ]);
  const posts = postsResult.data;

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">投稿履歴</h1>

      <FetchErrorNotice labels={failedLabels(postsResult)} />

      {!postsResult.failed && (
      <>

      <div className="text-sm text-neutral-600 dark:text-neutral-400">
        全{posts.length}件を表示
      </div>

      <div className="overflow-x-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 text-neutral-500 dark:text-neutral-400">
            <tr>
              <th className="px-4 py-2">サイト</th>
              <th className="px-4 py-2">WP投稿ID</th>
              <th className="px-4 py-2">スラッグ</th>
              <th className="px-4 py-2">ステータス</th>
              <th className="px-4 py-2">最終投稿日時</th>
            </tr>
          </thead>
          <tbody>
            {posts.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-6 text-center text-neutral-600 dark:text-neutral-400">
                  投稿履歴はまだありません(VSCode拡張から投稿すると表示されます)
                </td>
              </tr>
            )}
            {posts.map((post) => (
              <tr key={post.id} className="border-b border-neutral-100 dark:border-neutral-800 last:border-0 cursor-pointer hover:bg-neutral-50 dark:hover:bg-neutral-800 hover:shadow-sm transition-colors">
                <td className="px-4 py-2">{post.siteName}</td>
                <td className="px-4 py-2 font-mono">{post.wpPostId}</td>
                <td className="px-4 py-2">{post.slug ?? "-"}</td>
                <td className="px-4 py-2">
                  <span className="rounded-full bg-neutral-100 dark:bg-neutral-800 px-2 py-0.5 text-xs">{post.status}</span>
                </td>
                <td className="px-4 py-2 text-neutral-500 dark:text-neutral-400">
                  {post.lastPublishedAt ? (
                    <ViewerDateTime iso={post.lastPublishedAt} personalTimeZone={timezone} />
                  ) : (
                    "-"
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      </>
      )}
    </div>
  );
}
