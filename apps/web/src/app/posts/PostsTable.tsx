"use client";

import { useMemo, useState } from "react";
import type { PostSummary } from "@/lib/apiClient";
import { ViewerDateTime } from "@/components/ViewerDateTime";

type SortColumn = "siteName" | "status" | "lastPublishedAt" | null;
type SortOrder = "asc" | "desc";

export function PostsTable({ posts: initialPosts, timezone }: { posts: PostSummary[]; timezone: string | null }) {
  const [sortBy, setSortBy] = useState<SortColumn>(null);
  const [sortOrder, setSortOrder] = useState<SortOrder>("asc");

  const sortedPosts = useMemo(() => {
    if (sortBy) {
      const sorted = [...initialPosts];
      sorted.sort((a, b) => {
        let compareResult = 0;
        if (sortBy === "siteName") {
          compareResult = a.siteName.localeCompare(b.siteName);
        } else if (sortBy === "status") {
          compareResult = a.status.localeCompare(b.status);
        } else if (sortBy === "lastPublishedAt") {
          const aDate = a.lastPublishedAt ? new Date(a.lastPublishedAt).getTime() : 0;
          const bDate = b.lastPublishedAt ? new Date(b.lastPublishedAt).getTime() : 0;
          compareResult = aDate - bDate;
        }
        return sortOrder === "asc" ? compareResult : -compareResult;
      });
      return sorted;
    }
    return initialPosts;
  }, [initialPosts, sortBy, sortOrder]);

  const handleColumnSort = (column: SortColumn) => {
    if (sortBy === column) {
      setSortOrder(sortOrder === "asc" ? "desc" : "asc");
    } else {
      setSortBy(column);
      setSortOrder("asc");
    }
  };

  const renderSortIndicator = (column: SortColumn) => {
    if (sortBy !== column) return null;
    return sortOrder === "asc" ? " ↑" : " ↓";
  };

  return (
    <div className="overflow-x-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 text-neutral-500 dark:text-neutral-400">
          <tr>
            <th className="cursor-pointer px-4 py-2 hover:bg-neutral-100 dark:hover:bg-neutral-800" onClick={() => handleColumnSort("siteName")}>
              サイト{renderSortIndicator("siteName")}
            </th>
            <th className="px-4 py-2">WP投稿ID</th>
            <th className="px-4 py-2">スラッグ</th>
            <th className="cursor-pointer px-4 py-2 hover:bg-neutral-100 dark:hover:bg-neutral-800" onClick={() => handleColumnSort("status")}>
              ステータス{renderSortIndicator("status")}
            </th>
            <th className="px-4 py-2">カテゴリ</th>
            <th className="cursor-pointer px-4 py-2 hover:bg-neutral-100 dark:hover:bg-neutral-800" onClick={() => handleColumnSort("lastPublishedAt")}>
              最終投稿日時{renderSortIndicator("lastPublishedAt")}
            </th>
            <th className="px-4 py-2">公開予定日時</th>
          </tr>
        </thead>
        <tbody>
          {sortedPosts.length === 0 && (
            <tr>
              <td colSpan={7} className="px-4 py-6 text-center text-neutral-600 dark:text-neutral-400">
                投稿履歴はまだありません(VSCode拡張から投稿すると表示されます)
              </td>
            </tr>
          )}
          {sortedPosts.map((post) => (
            <tr key={post.id} className="border-b border-neutral-100 dark:border-neutral-800 last:border-0">
              <td className="px-4 py-2">{post.siteName}</td>
              <td className="px-4 py-2 font-mono">{post.wpPostId}</td>
              <td className="px-4 py-2">{post.slug ?? "-"}</td>
              <td className="px-4 py-2">
                <span className="rounded-full bg-neutral-100 dark:bg-neutral-800 px-2 py-0.5 text-xs">{post.status}</span>
              </td>
              <td className="px-4 py-2">{post.categories.length > 0 ? post.categories.join(", ") : "-"}</td>
              <td className="px-4 py-2 text-neutral-500 dark:text-neutral-400">
                {post.lastPublishedAt ? <ViewerDateTime iso={post.lastPublishedAt} personalTimeZone={timezone} /> : "-"}
              </td>
              <td className="px-4 py-2 text-neutral-500 dark:text-neutral-400">
                {post.publishScheduledAt ? <ViewerDateTime iso={post.publishScheduledAt} personalTimeZone={timezone} /> : "-"}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
