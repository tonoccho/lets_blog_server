package com.letsblog.content.service;

import org.springframework.stereotype.Service;

/**
 * 投稿本文の最終HTML全体を、システムがレンダリングした範囲であることを示す固定クラス
 * (lets-blog-rendered)を持つ&lt;div&gt;で囲む。legacy-apiのRenderedContentWrapperServiceと同じ実装
 * (#576でcontent-serviceへ移管。cssSelectorPrefixの解決先をProjectService(legacy-api)から
 * 本サービス自身が所有するProjectContentSettingsServiceへ切り替えた)。
 *
 * CustomTagServiceが生成する統合CSSバンドルは、プロジェクトのcssSelectorPrefixを
 * 子孫結合子(`.prefix 元セレクタ`)で各セレクタの先頭に付与する(issue #298)。この形式は
 * プリフィックスクラスを持つ祖先要素の存在を前提とするため、この&lt;div&gt;に同じプリフィックス
 * クラスも付与することで、統合CSSのセレクタと実際にレンダリングされた要素を一致させる。
 */
@Service
public class RenderedContentWrapperService {

    public static final String WRAPPER_CLASS = "lets-blog-rendered";

    private final ProjectContentSettingsService projectContentSettingsService;

    public RenderedContentWrapperService(ProjectContentSettingsService projectContentSettingsService) {
        this.projectContentSettingsService = projectContentSettingsService;
    }

    /**
     * projectIdが指定されていれば、プロジェクトのcssSelectorPrefixもクラスに含める。
     * html未指定(null/空)の場合は何もラップせずそのまま返す。
     */
    public String wrap(String html, Long projectId) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        String className = projectId == null
                ? WRAPPER_CLASS
                : WRAPPER_CLASS + " " + projectContentSettingsService.resolveCssSelectorPrefix(projectId);
        return "<div class=\"" + className + "\">" + html + "</div>";
    }
}
