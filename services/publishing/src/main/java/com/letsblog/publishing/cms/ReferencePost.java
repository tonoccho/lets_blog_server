package com.letsblog.publishing.cms;

/**
 * 記事プレビュー(ArticlePreviewService)がテーマCSS/DOM構造の流用元として使う、サイト内の
 * 最新公開記事。title/contentはWordPress REST API(wp/v2/posts)の title.rendered/content.rendered
 * 相当(the_title/the_contentフィルタ適用後)の値。
 */
public record ReferencePost(String id, String link, String title, String content) {
}
