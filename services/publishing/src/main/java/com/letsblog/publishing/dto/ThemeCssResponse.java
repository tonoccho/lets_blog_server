package com.letsblog.publishing.dto;

/**
 * テーマCSS取得結果。sourceはCSSの取得経路(issue #1368): {@link #SOURCE_SSH}はSSH管理サイトの
 * リモートホストから取得、{@link #SOURCE_HTTP}は公開URLへのHTTP取得(SSH以外のサイト、または
 * SSH経路が失敗してのフォールバック)。取得自体に失敗した(available=false)場合はnull。
 */
public record ThemeCssResponse(String css, boolean available, String reason, String source) {

    public static final String SOURCE_SSH = "SSH";
    public static final String SOURCE_HTTP = "HTTP";

    /** 経路情報を持たない応答(取得失敗)。 */
    public ThemeCssResponse(String css, boolean available, String reason) {
        this(css, available, reason, null);
    }
}
