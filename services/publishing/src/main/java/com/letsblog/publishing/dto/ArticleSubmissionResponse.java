package com.letsblog.publishing.dto;

import com.letsblog.publishing.domain.ArticleReviewState;

/**
 * 記事提出APIの応答(issue #1339)。{@code created}はこの呼び出しでPRを新規作成したか(偽なら既存PRの再提出)。
 * {@code submittedByUserId}は記録された提出者(Let's Blogのユーザー)。
 */
public record ArticleSubmissionResponse(
        int prNumber, String url, ArticleReviewState state, Long submittedByUserId, boolean created) {
}
