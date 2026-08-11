package com.letsblog.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * サイト内の既存記事ページを骨格として流用し、タイトル・本文・アイキャッチを
 * プレビュー対象記事の内容へ差し替えるためのリクエスト。
 */
public record RenderSkeletonRequest(
        @NotNull String title,
        @NotNull String contentHtml,
        String featuredImageDataUri,
        Long siteId
) {
}
