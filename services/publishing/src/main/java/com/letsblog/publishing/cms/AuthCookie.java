package com.letsblog.publishing.cms;

/**
 * WordPressのログイン済みセッションを表すCookie(name/value)。
 * {@link CmsAdapter#generateAuthCookie(CmsCredentials)}が返し、記事プレビューで
 * 非公開(private)投稿の実ページを閲覧するためにPlaywrightのブラウザコンテキストへ注入する。
 */
public record AuthCookie(String name, String value) {
}
