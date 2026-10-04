package com.letsblog.publishing.dto;

import com.letsblog.publishing.domain.ArticleReviewState;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 記事差し戻し(issue #1344)の応答。{@code commentId}は指摘として投稿したPRコメントのID、
 * {@code rejectedByUserId}は差し戻したLet's Blogユーザー。
 */
public record ArticleRejectResponse(
        int prNumber, ArticleReviewState state, Long commentId, Long rejectedByUserId, Instant rejectedAt) {

    /** DB / エンティティの LocalDateTime は UTC の壁時計(#1257)。公開レスポンスは Z 終端 RFC 3339 で返す(#1611)。 */
    public static ArticleRejectResponse of(
            int prNumber, ArticleReviewState state, Long commentId, Long rejectedByUserId, LocalDateTime rejectedAt) {
        return new ArticleRejectResponse(
                prNumber, state, commentId, rejectedByUserId,
                rejectedAt == null ? null : rejectedAt.toInstant(ZoneOffset.UTC));
    }
}
