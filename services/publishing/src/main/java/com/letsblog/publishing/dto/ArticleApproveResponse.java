package com.letsblog.publishing.dto;

import com.letsblog.publishing.domain.ArticleReviewState;

/**
 * レビュー完了(issue #1343)の応答。{@code productionPostUrl}は本番環境に投稿した記事のURL、{@code wpPostId}は
 * 本番環境の投稿ID、{@code merged}はPull Requestをマージしたか(成功した応答では常にtrue)、
 * {@code branchDeleted}はheadブランチを削除できたか(削除に失敗しても公開・マージ済みの結果は有効なのでfalseで返す)。
 */
public record ArticleApproveResponse(
        int prNumber, ArticleReviewState state, String productionPostUrl, String wpPostId,
        boolean merged, boolean branchDeleted) {
}
