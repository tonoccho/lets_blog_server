package com.letsblog.media.dto;

/**
 * publishing-service向けの内部ブリッジ応答(issue #707)。記事投稿時に画像をリサイズする長編の
 * 目標px({@code ProjectService#resolveArticleImageLongEdgePx}と同じ値)。
 */
public record ArticleImageLongEdgePxBridgeResponse(int value) {
}
