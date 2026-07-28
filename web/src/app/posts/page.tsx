import { listPosts } from "@/lib/apiClient";

export default async function PostsPage() {
  const posts = await listPosts().catch(() => []);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">投稿履歴</h1>

      <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
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
                <td colSpan={5} className="px-4 py-6 text-center text-neutral-600">
                  投稿履歴はまだありません(VSCode拡張から投稿すると表示されます)
                </td>
              </tr>
            )}
            {posts.map((post) => (
              <tr key={post.id} className="border-b border-neutral-100 last:border-0">
                <td className="px-4 py-2">{post.siteName}</td>
                <td className="px-4 py-2 font-mono">{post.wpPostId}</td>
                <td className="px-4 py-2">{post.slug ?? "-"}</td>
                <td className="px-4 py-2">
                  <span className="rounded-full bg-neutral-100 px-2 py-0.5 text-xs">{post.status}</span>
                </td>
                <td className="px-4 py-2 text-neutral-500">
                  {post.lastPublishedAt ? new Date(post.lastPublishedAt).toLocaleString("ja-JP") : "-"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
