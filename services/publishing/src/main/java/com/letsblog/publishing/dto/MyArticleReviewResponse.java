package com.letsblog.publishing.dto;

import com.letsblog.publishing.domain.ArticleReviewState;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 自分宛のレビュー一覧(issue #1344)の1行。{@code rejectComment}と{@code rejectedAt}は状態が差し戻しの行だけに付く。
 * {@code rejectComment}はGitHubのコメント本文そのままで、GitHub側で消されていればnull。
 */
public record MyArticleReviewResponse(
        int prNumber, String articleSlug, ArticleReviewState state, Instant submittedAt,
        String rejectComment, Instant rejectedAt) {

    /** DB / エンティティの LocalDateTime は UTC の壁時計(#1257)。公開レスポンスは Z 終端 RFC 3339 で返す(#1611)。 */
    public static MyArticleReviewResponse of(
            int prNumber, String articleSlug, ArticleReviewState state, LocalDateTime submittedAt,
            String rejectComment, LocalDateTime rejectedAt) {
        return new MyArticleReviewResponse(
                prNumber, articleSlug, state, toInstant(submittedAt), rejectComment, toInstant(rejectedAt));
    }

    private static Instant toInstant(LocalDateTime utcWallClock) {
        return utcWallClock == null ? null : utcWallClock.toInstant(ZoneOffset.UTC);
    }
}
