package com.letsblog.publishing.dto;

import com.letsblog.publishing.domain.ArticleReviewState;
import java.time.LocalDateTime;

/**
 * 記事差し戻し(issue #1344)の応答。{@code commentId}は指摘として投稿したPRコメントのID、
 * {@code rejectedByUserId}は差し戻したLet's Blogユーザー。
 */
public record ArticleRejectResponse(
        int prNumber, ArticleReviewState state, Long commentId, Long rejectedByUserId, LocalDateTime rejectedAt) {
}
