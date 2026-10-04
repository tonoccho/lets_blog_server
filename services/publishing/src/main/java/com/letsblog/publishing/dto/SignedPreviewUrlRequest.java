package com.letsblog.publishing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 投稿を作らない署名付きプレビュー URL の発行依頼(issue #1561)。本文HTMLは、呼び出し側が
 * 既にレンダリングした結果(content-serviceの{@code /render})をそのまま渡す。
 */
public record SignedPreviewUrlRequest(
        /** 対象サイト。未指定ならプロジェクトのマスター環境のサイト。 */
        Long siteId,
        @NotBlank String title,
        @NotNull String contentHtml,
        /** front matterのcategories(名前)。プレビューの表示にだけ使い、CMSへ作らない。 */
        List<String> categories,
        /** front matterのtags(名前)。プレビューの表示にだけ使い、CMSへ作らない。 */
        List<String> tags,
        /** アイキャッチ(画像のdata URI)。メディアとしてアップロードせず、表示にだけ使う。 */
        String featuredImageDataUri,
        /** 有効期限(秒)。省略時はプラグインの規定値。 */
        @Min(1) @Max(86400) Integer ttlSeconds
) {
}
