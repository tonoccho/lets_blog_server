import type { ArticleReviewStateName } from "@/lib/apiClient";

/**
 * 記事レビューの状態の表示文言(issue #1677)。一覧の「状態」列と、状態が変わる操作の結果表示(#1346)が
 * 同じ文言を使えるよう、ここ1か所に置く。`null` は `article_reviews` に記録の無い PR(未提出)。
 */
export const ARTICLE_REVIEW_STATE_LABELS: Record<ArticleReviewStateName, string> = {
  SUBMITTED: "提出済み",
  IN_REVIEW: "レビュー中",
  CHANGES_REQUESTED: "差し戻し",
  PUBLISHED: "公開済み",
};

export const ARTICLE_REVIEW_STATE_NONE_LABEL = "未提出";

export function articleReviewStateLabel(state: ArticleReviewStateName | null): string {
  return state === null ? ARTICLE_REVIEW_STATE_NONE_LABEL : ARTICLE_REVIEW_STATE_LABELS[state];
}
