package com.letsblog.publishing.dto;

import com.letsblog.publishing.domain.ArticleReviewState;

/**
 * レビュー開始(issue #1341)の応答。{@code testPostUrl}はテスト環境に投稿した記事のURL(確認先)、
 * {@code reviewedByUserId}はAPIを呼んだLet's Blogユーザー、{@code wpPostId}はテスト環境の投稿ID。
 */
public record ArticleReviewResponse(
        int prNumber, ArticleReviewState state, String testPostUrl, Long reviewedByUserId, String wpPostId) {
}
