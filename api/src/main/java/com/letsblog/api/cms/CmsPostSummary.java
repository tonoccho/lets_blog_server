package com.letsblog.api.cms;

/** ポスト/ページ管理タブの一覧比較用。postType: "post" または "page"。 */
public record CmsPostSummary(String id, String title, String slug, String status, String postType) {
}
