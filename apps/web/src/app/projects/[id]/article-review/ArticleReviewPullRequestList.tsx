"use client";

import { useState } from "react";
import type { ArticleReviewPullRequest } from "@/lib/apiClient";
import { ViewerDateTime } from "@/components/ViewerDateTime";
import { ArticleReviewButton } from "./ArticleReviewButton";
import { ArticleReviewDecision } from "./ArticleReviewDecision";
import { articleReviewStateLabel } from "./articleReviewStateLabels";

/** レビュー待ちの Pull Request を表で並べ、各行から「レビュー」を始め、「レビュー完了」「記事差し戻し」で結果を返せる(issue #1340、#1345、#1346、#1677 で状態列)。日時は閲覧者のタイムゾーンで表示する。 */
export function ArticleReviewPullRequestList({
  projectId,
  pullRequests,
  timezone,
}: {
  projectId: number;
  pullRequests: ArticleReviewPullRequest[];
  timezone: string | null;
}) {
  // 操作が成功した PR は、取り直した一覧から消えても(レビュー完了はマージして閉じる)結果つきで残す
  const [retained, setRetained] = useState<ArticleReviewPullRequest[]>([]);
  const retain = (pr: ArticleReviewPullRequest) =>
    setRetained((current) => (current.some((r) => r.number === pr.number) ? current : [...current, pr]));
  const rows = [...pullRequests, ...retained.filter((r) => !pullRequests.some((pr) => pr.number === r.number))];

  if (rows.length === 0) {
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
            <th className="px-4 py-2 font-medium">状態</th>
            <th className="px-4 py-2 font-medium">作成日時</th>
            <th className="px-4 py-2 font-medium">リンク</th>
            <th className="px-4 py-2 font-medium">レビュー</th>
            <th className="px-4 py-2 font-medium">レビュー結果</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((pr) => (
            <tr key={pr.number} className="border-b border-neutral-100 dark:border-neutral-800 last:border-b-0">
              <td className="px-4 py-2 whitespace-nowrap">#{pr.number}</td>
              <td className="px-4 py-2">{pr.title}</td>
              <td className="px-4 py-2 font-mono text-xs">{pr.headBranch}</td>
              <td className="px-4 py-2 whitespace-nowrap">{articleReviewStateLabel(pr.state)}</td>
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
              <td className="px-4 py-2">
                <ArticleReviewButton projectId={projectId} prNumber={pr.number} />
              </td>
              <td className="px-4 py-2">
                <ArticleReviewDecision projectId={projectId} prNumber={pr.number} onSucceeded={() => retain(pr)} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
