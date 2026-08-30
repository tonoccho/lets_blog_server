package com.letsblog.content.dto;

/**
 * legacy-apiのPostPublishService(#575の対象になるまで引き続きlegacy-apiに残る)が、公開パイプライン中の
 * カスタムタグ/組み込みタグ(blogcard/amazon/recharts)展開を本サービスへ委譲するための内部ブリッジ
 * リクエスト(issue #576)。CMS画像アップロード(plantumlの埋め込み・画像参照差し替え)より前の段階
 * (投稿本文の確定前)にlegacy-api側から呼ばれる。
 */
public record PreImageRenderRequest(String markdown, Long projectId, boolean productionSite) {
}
