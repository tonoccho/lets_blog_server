package com.letsblog.content.service;

import com.letsblog.content.client.ProjectBridgeClient;
import com.letsblog.content.dto.TagDesignColors;
import com.letsblog.content.markdown.MarkdownRenderer;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [toc]組み込みタグ(#146、flexmark-ext-tocが解釈する)向けに、プロジェクトごとにカスタマイズされた
 * デザイン(#150)のCSSをCustomTagServiceの統合CSSバンドルへ提供する(buildStyle)。[toc]自体の展開は
 * flexmarkが行う(MarkdownRenderer)。CSSは統合CSSバンドル経由でのみ提供し、記事本文へは注入しない
 * (CustomTagRenderServiceと同じ方針)。legacy-apiのTocStyleRenderServiceと同じ実装(issue #576で
 * content-serviceへ移管)。tag_design_settingsテーブル自体はlegacy-apiに残るドメインのため、
 * カスタムHTMLテンプレートの解決は{@link ProjectBridgeClient}経由の内部ブリッジで行う。
 *
 * カスタムHTMLテンプレート(issue #165)は、flexmarkが目次を展開した後のHTML全体に対して
 * 後処理で適用する(applyHtmlTemplate)。目次はH2〜H4の見出し構造から動的に生成される
 * 入れ子の&lt;ul&gt;であり、blogcard/amazonのような固定データ項目を持たないため、
 * テンプレートは目次全体を1つの{{toc}}プレースホルダとして受け取る「外枠のみのカスタマイズ」に限定する。
 */
@Service
public class TocStyleRenderService {

    private static final String TOC_LIST_OPEN_TAG = "<ul class=\"" + MarkdownRenderer.TOC_LIST_CLASS + "\">";

    private static final Pattern TOC_TOKEN_PATTERN =
            Pattern.compile("<ul(?:\\s[^>]*)?>|</ul>|<li(?:\\s[^>]*)?>|</li>|<a\\s+href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    private final ProjectBridgeClient projectBridgeClient;
    private final CurrentActorService currentActorService;

    public TocStyleRenderService(ProjectBridgeClient projectBridgeClient, CurrentActorService currentActorService) {
        this.projectBridgeClient = projectBridgeClient;
        this.currentActorService = currentActorService;
    }

    /**
     * markdownRenderer.render()でHTML化した後に呼ぶ。カスタムHTMLテンプレートが設定されていれば、
     * flexmarkが生成した目次の&lt;ul class="lb-toc-list"&gt;...&lt;/ul&gt;ブロック全体(見出し階層に応じた
     * 入れ子の&lt;ul&gt;を含む)を、テンプレート中の{{toc}}と置き換えたHTMLに差し替える。
     * 目次自体が存在しない場合はhtmlをそのまま返す。
     * 目次は、見出しの構造を持つ目印(issue #1563)で挟む。テンプレートの設定有無に関わらず付けるのは、
     * 投稿後にテンプレートが設定・変更・解除されても、プラグインが表示時に新しい設定で展開し直せるようにするため。
     */
    public String applyHtmlTemplate(String html, Long projectId) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        String template = projectBridgeClient
                .resolveTagDesign(projectId, "TOC", currentActorService.getAuthorizationHeader())
                .htmlTemplate();

        int start = html.indexOf(TOC_LIST_OPEN_TAG);
        if (start < 0) {
            return html;
        }
        int end = findMatchingCloseTag(html, start + TOC_LIST_OPEN_TAG.length());
        if (end < 0) {
            return html;
        }

        String tocBlock = html.substring(start, end);
        String replacement = template == null ? tocBlock : template.replace("{{toc}}", tocBlock);
        List<Map<String, Object>> items = parseItems(tocBlock);
        if (items == null) {
            // 構造を読み取れない目次は目印を付けず、投稿時点のHTMLのまま表示する
            return html.substring(0, start) + replacement + html.substring(end);
        }
        String marked = EmbedMarker.wrap("TOC", Map.of("items", items), replacement, "\n");
        return html.substring(0, start) + marked + html.substring(end);
    }

    /**
     * 目次の&lt;ul&gt;ブロックから、見出しの入れ子の構造(text・href・children)を読み取る(issue #1563)。
     * 読み取れない(入れ子の&lt;ul&gt;が項目の外にある、項目にリンクが無い等)場合はnull。
     */
    private List<Map<String, Object>> parseItems(String tocBlock) {
        List<Map<String, Object>> root = new ArrayList<>();
        Deque<List<Map<String, Object>>> lists = new ArrayDeque<>();
        Deque<Map<String, Object>> openItems = new ArrayDeque<>();
        Matcher matcher = TOC_TOKEN_PATTERN.matcher(tocBlock);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.startsWith("<ul")) {
                List<Map<String, Object>> list = lists.isEmpty() ? root : childrenOfCurrent(openItems);
                if (list == null) {
                    return null;
                }
                lists.push(list);
            } else if (token.equals("</ul>")) {
                lists.pop();
            } else if (token.startsWith("<li")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("text", null);
                item.put("href", null);
                item.put("children", new ArrayList<Map<String, Object>>());
                lists.peek().add(item);
                openItems.push(item);
            } else if (token.equals("</li>")) {
                if (openItems.isEmpty() || openItems.peek().get("href") == null) {
                    return null;
                }
                openItems.pop();
            } else {
                if (openItems.isEmpty()) {
                    return null;
                }
                openItems.peek().put("href", HtmlUtils.htmlUnescape(matcher.group(1)));
                openItems.peek().put("text", HtmlUtils.htmlUnescape(HTML_TAG_PATTERN.matcher(matcher.group(2)).replaceAll("")));
            }
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> childrenOfCurrent(Deque<Map<String, Object>> openItems) {
        return openItems.isEmpty() ? null : (List<Map<String, Object>>) openItems.peek().get("children");
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
        return "." + MarkdownRenderer.TOC_LIST_CLASS
                + "{list-style:disc;list-style-position:inside;margin:1em 0;padding:12px 16px;"
                + "border-radius:8px;background:" + colors.backgroundColor() + ";}"
                + "." + MarkdownRenderer.TOC_LIST_CLASS
                + " ul{list-style:disc;list-style-position:inside;margin:0;padding-left:20px;}"
                + "." + MarkdownRenderer.TOC_LIST_CLASS + " li{margin:4px 0;}"
                + "." + MarkdownRenderer.TOC_LIST_CLASS + " li::marker{color:#000;}"
                + "." + MarkdownRenderer.TOC_LIST_CLASS + " a{color:" + colors.textColor()
                + ";text-decoration:none;}"
                + "." + MarkdownRenderer.TOC_LIST_CLASS + " a:hover{color:" + colors.accentColor()
                + ";text-decoration:underline;}";
    }
}
