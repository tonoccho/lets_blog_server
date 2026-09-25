package com.letsblog.content.dto;

/** {@link com.letsblog.content.service.PreviewSkeletonFetcher#fetchAndSplice}への内部ブリッジリクエスト。 */
public record FetchAndSpliceRequest(
        String url,
        String titleRendered,
        String contentRendered,
        String ourTitle,
        String ourContentHtml,
        String featuredImageDataUri) {
}
