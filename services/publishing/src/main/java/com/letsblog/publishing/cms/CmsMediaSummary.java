package com.letsblog.publishing.cms;

/** ガベージコレクション画面のメディア一覧用(issue #500)。 */
public record CmsMediaSummary(String id, String guid, String title, String mimeType, String uploadedAt) {
}
