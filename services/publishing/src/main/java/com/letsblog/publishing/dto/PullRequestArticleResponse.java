package com.letsblog.publishing.dto;

import java.util.List;

/**
 * PRのheadから取得した記事1本分(issue #1338)。本文はMarkdownのまま返し、HTMLへのレンダリングは
 * 投稿時に既存の公開パイプラインが行う。assetsは名前とサイズのみで、中身は投稿(後続Issue)が取り直す。
 */
public record PullRequestArticleResponse(String slug, FrontMatter frontMatter, String body, List<Asset> assets) {

    /** front matterの解析結果。拡張側{@code LetsBlogFrontMatter}のうち投稿が使う項目。 */
    public record FrontMatter(String title, String slug, String status, List<String> categories,
            List<String> tags, String featuredImage, String publishScheduledAt) {
    }

    /** {@code articles/<slug>/assets/}配下のファイル。{@code name}はassets/からの相対パス。 */
    public record Asset(String name, long size) {
    }
}
