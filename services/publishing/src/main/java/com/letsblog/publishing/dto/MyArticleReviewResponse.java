package com.letsblog.publishing.dto;

import com.letsblog.publishing.domain.ArticleReviewState;
import java.time.LocalDateTime;

/**
 * 自分宛のレビュー一覧(issue #1344)の1行。{@code rejectComment}と{@code rejectedAt}は状態が差し戻しの行だけに付く。
 * {@code rejectComment}はGitHubのコメント本文そのままで、GitHub側で消されていればnull。
 */
public record MyArticleReviewResponse(
        int prNumber, String articleSlug, ArticleReviewState state, LocalDateTime submittedAt,
        String rejectComment, LocalDateTime rejectedAt) {
}
