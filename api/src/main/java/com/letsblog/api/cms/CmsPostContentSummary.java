package com.letsblog.api.cms;

/**
 * ガベージコレクション画面のメディア参照スキャン用(issue #500)。公開投稿タイプ(post/page等)
 * 1件分の、メディア参照検出に必要な最小限のフィールド。thumbnailIdはアイキャッチ未設定時は空文字。
 */
public record CmsPostContentSummary(String id, String postType, String status, String content, String thumbnailId) {
}
