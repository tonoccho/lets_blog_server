package com.letsblog.media.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 記事投稿時に画像をリサイズする長編の目標pxの更新リクエスト(issue #291)。
 * nullはアプリ全体のデフォルト(既定1300px)へのフォールバックを意味する。
 */
public record UpdateArticleImageResizeDefaultRequest(
        @Min(64) @Max(4096) Integer defaultArticleImageLongEdgePx
) {
}
