"use server";

import { approveArticleReview, rejectArticleReview, startArticleReview } from "@/lib/apiClient";
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

export interface ApproveArticleReviewState {
  productionPostUrl?: string;
  branchDeleted?: boolean;
  error?: string;
}

/**
 * 「レビュー完了」(issue #1346)。本番登録・マージ・ブランチ削除を行い、本番の投稿 URL を返す。
 * 失敗はサーバが返した理由を `error` として値で返す(画面に出すため)。
 */
export async function approveArticleReviewAction(projectId: number, prNumber: number): Promise<ApproveArticleReviewState> {
  await requireAdminSession();

  try {
    const result = await approveArticleReview(projectId, prNumber);
    return { productionPostUrl: result.productionPostUrl, branchDeleted: result.branchDeleted };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export interface RejectArticleReviewState {
  rejected?: true;
  error?: string;
}

/**
 * 「記事差し戻し」(issue #1346)。指摘事項は必須で、空白だけならサーバへ送らずに入力が必要な旨を返す。
 * 失敗はサーバが返した理由を `error` として値で返す。
 */
export async function rejectArticleReviewAction(
  projectId: number,
  prNumber: number,
  feedback: string
): Promise<RejectArticleReviewState> {
  await requireAdminSession();

  if (feedback.trim() === "") {
    return { error: "指摘事項を入力してください" };
  }

  try {
    await rejectArticleReview(projectId, prNumber, feedback);
    return { rejected: true };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}
