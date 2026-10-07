"use client";

import { useState, useTransition } from "react";
import { startArticleReviewAction, type StartArticleReviewState } from "./actions";

/**
 * 一覧の 1 行分の「レビュー」ボタン(issue #1345)。
 * テスト環境への投稿は画像のアップロードを伴い時間がかかるため、処理中は進行中を表示して押せなくする。
 */
export function ArticleReviewButton({ projectId, prNumber }: { projectId: number; prNumber: number }) {
  const [pending, startTransition] = useTransition();
  const [result, setResult] = useState<StartArticleReviewState>({});

  const review = () => {
    setResult({});
    startTransition(async () => {
      setResult(await startArticleReviewAction(projectId, prNumber));
    });
  };

  return (
    <div className="flex flex-col items-start gap-1">
      <button
        type="button"
        onClick={review}
        disabled={pending}
        className="rounded bg-neutral-900 px-3 py-1 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "テスト環境へ投稿しています…" : "レビュー"}
      </button>
      {pending && (
        <p role="status" className="text-xs text-neutral-600 dark:text-neutral-400">
          テスト環境へ投稿しています。完了までお待ちください。
        </p>
      )}
      {result.testPostUrl && (
        <a
          href={result.testPostUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="break-all text-blue-600 dark:text-blue-400 hover:underline"
        >
          {result.testPostUrl}
        </a>
      )}
      {result.error && (
        <p role="alert" className="text-red-600 dark:text-red-400">
          {result.error}
        </p>
      )}
    </div>
  );
}
