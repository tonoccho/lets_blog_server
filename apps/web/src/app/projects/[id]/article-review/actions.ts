"use server";

import { startArticleReview } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface StartArticleReviewState {
  testPostUrl?: string;
  error?: string;
}

/**
 * 「レビュー」ボタン(issue #1345)。PR の記事をテスト環境へ投稿し、確認先 URL を返す。
 * 失敗はサーバが返した理由を `error` として値で返す(画面に出すため)。
 */
export async function startArticleReviewAction(projectId: number, prNumber: number): Promise<StartArticleReviewState> {
  await requireAdminSession();

  try {
    const result = await startArticleReview(projectId, prNumber);
    return { testPostUrl: result.testPostUrl };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}
