import { listPosts } from "@/lib/apiClient";
import { getViewerTimeZone } from "@/lib/session";
import { PostsTable } from "./PostsTable";

export default async function PostsPage() {
  const [posts, timezone] = await Promise.all([listPosts().catch(() => []), getViewerTimeZone()]);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">投稿履歴</h1>

      <PostsTable posts={posts} timezone={timezone} />
    </div>
  );
}
