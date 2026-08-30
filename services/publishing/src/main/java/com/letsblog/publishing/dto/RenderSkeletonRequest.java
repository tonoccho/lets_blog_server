package com.letsblog.publishing.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * サイト内の既存記事ページを骨格として流用し、タイトル・本文・アイキャッチを
 * プレビュー対象記事の内容へ差し替えるためのリクエスト。
 */
public record RenderSkeletonRequest(
        @NotNull String title,
        @NotNull String contentHtml,
        String featuredImageDataUri,
        Long siteId,
        /**
         * ローカル/テスト環境で非公開投稿としてプレビューを表示する経路(renderRealPrivatePost)向け。
         * 既に作成済みのプレビュー投稿があれば、新規作成せずそのIDを更新する(呼び出しの都度、
         * 実投稿を積み上げないため)。未指定の場合は新規作成する。
         */
        String existingPreviewPostId,
        /** front matterのslug。renderRealPrivatePost経路でのみ使う(スクレイプ&splice経路には影響しない)。 */
        String slug,
        /** front matterのcategories(名前)。renderRealPrivatePost経路でのみ使う。 */
        List<String> categories,
        /** front matterのtags(名前)。renderRealPrivatePost経路でのみ使う。 */
        List<String> tags
) {
}
