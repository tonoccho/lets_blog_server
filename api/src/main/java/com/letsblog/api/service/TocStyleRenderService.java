package com.letsblog.api.service;

import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.dto.TagDesignColors;
import com.letsblog.api.markdown.MarkdownRenderer;
import org.springframework.stereotype.Service;

/**
 * [toc]組み込みタグ(#146、flexmark-ext-tocが解釈する)向けに、プロジェクトごとにカスタマイズされた
 * デザイン(#150)のCSSをCustomTagServiceの統合CSSバンドルへ提供する(buildStyle)。[toc]自体の展開は
 * flexmarkが行う(MarkdownRenderer)。CSSは統合CSSバンドル経由でのみ提供し、記事本文へは注入しない
 * (CustomTagRenderServiceと同じ方針)。
 *
 * カスタムHTMLテンプレート(issue #165)は、flexmarkが目次を展開した後のHTML全体に対して
 * 後処理で適用する(applyHtmlTemplate)。目次はH2〜H4の見出し構造から動的に生成される
 * 入れ子の&lt;ul&gt;であり、blogcard/amazonのような固定データ項目を持たないため、
 * テンプレートは目次全体を1つの{{toc}}プレースホルダとして受け取る「外枠のみのカスタマイズ」に限定する。
 */
@Service
public class TocStyleRenderService {

    private static final String TOC_LIST_OPEN_TAG = "<ul class=\"" + MarkdownRenderer.TOC_LIST_CLASS + "\">";

    private final TagDesignSettingService tagDesignSettingService;

    public TocStyleRenderService(TagDesignSettingService tagDesignSettingService) {
        this.tagDesignSettingService = tagDesignSettingService;
    }

    /**
     * markdownRenderer.render()でHTML化した後に呼ぶ。カスタムHTMLテンプレートが設定されていれば、
     * flexmarkが生成した目次の&lt;ul class="lb-toc-list"&gt;...&lt;/ul&gt;ブロック全体(見出し階層に応じた
     * 入れ子の&lt;ul&gt;を含む)を、テンプレート中の{{toc}}と置き換えたHTMLに差し替える。
     * 未設定、または目次自体が存在しない場合はhtmlをそのまま返す。
     */
    public String applyHtmlTemplate(String html, Long projectId) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        String template = tagDesignSettingService.resolveHtmlTemplate(projectId, EmbedTagType.TOC);
        if (template == null) {
            return html;
        }

        int start = html.indexOf(TOC_LIST_OPEN_TAG);
        if (start < 0) {
            return html;
        }
        int end = findMatchingCloseTag(html, start + TOC_LIST_OPEN_TAG.length());
        if (end < 0) {
            return html;
        }

        String tocBlock = html.substring(start, end);
        String replacement = template.replace("{{toc}}", tocBlock);
        return html.substring(0, start) + replacement + html.substring(end);
    }

    /**
     * fromIndex(外側の&lt;ul&gt;の開始タグ直後)から、入れ子の&lt;ul&gt;を数えながら対応する
     * &lt;/ul&gt;の直後の位置を探す。深さで対応付けるため、見出し階層による入れ子にも対応する。
     */
    private int findMatchingCloseTag(String html, int fromIndex) {
        int depth = 1;
        int i = fromIndex;
        while (i < html.length()) {
            int nextOpen = html.indexOf("<ul", i);
            int nextClose = html.indexOf("</ul>", i);
            if (nextClose < 0) {
                return -1;
            }
            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++;
                i = nextOpen + 3;
            } else {
                depth--;
                i = nextClose + 5;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * CustomTagServiceの統合CSS生成からも呼ばれるためpackage-private。
     * customCssが設定されていれば、色ベースの生成CSSの代わりにそちらを丸ごと使う(完全上書き、issue #165)。
     * 未設定の場合のみ、背景色/テキスト色/アクセントカラーから組み立てる。
     */
    String buildStyle(TagDesignColors colors) {
        String customCss = colors.customCss();
        if (customCss != null && !customCss.isBlank()) {
            return customCss.trim();
        }
        return "." + MarkdownRenderer.TOC_LIST_CLASS + "{list-style:none;margin:1em 0;padding:12px 16px;"
                + "border-radius:8px;background:" + colors.backgroundColor() + ";}"
                + "." + MarkdownRenderer.TOC_LIST_CLASS + " ul{list-style:none;}"
                + "." + MarkdownRenderer.TOC_LIST_CLASS + " li{margin:4px 0;}"
                + "." + MarkdownRenderer.TOC_LIST_CLASS + " a{color:" + colors.textColor()
                + ";text-decoration:none;}"
                + "." + MarkdownRenderer.TOC_LIST_CLASS + " a:hover{color:" + colors.accentColor()
                + ";text-decoration:underline;}";
    }
}
