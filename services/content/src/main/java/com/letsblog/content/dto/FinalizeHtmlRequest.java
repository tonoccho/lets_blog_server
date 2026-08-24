package com.letsblog.content.dto;

/**
 * legacy-apiのPostPublishServiceが、CMS画像アップロード後の最終Markdown→HTML変換+目次/統合CSS
 * ラッパー適用を本サービスへ委譲するための内部ブリッジリクエスト(issue #576)。
 */
public record FinalizeHtmlRequest(String markdown, Long projectId) {
}
