package com.letsblog.content.service;

import com.letsblog.content.client.ProjectBridgeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #760: プロジェクトに紐付いていないサイトへの公開では、finalize-htmlがprojectId=nullのまま
 * applyHtmlTemplateを呼ぶ。projectIdはそのままタグデザイン解決へ渡し(受け側のlegacy-api/
 * project-serviceが固定のデフォルト値を返す暫定対応、issue #763)、レンダリングは成功する必要がある。
 */
@ExtendWith(MockitoExtension.class)
class TocStyleRenderServiceTest {

    private static final String HTML_WITH_TOC =
            "<ul class=\"lb-toc-list\"><li><a href=\"#a\">A</a></li></ul>\n<h2 id=\"a\">A</h2>";

    @Mock
    private ProjectBridgeClient projectBridgeClient;

    @Mock
    private CurrentActorService currentActorService;

    private TocStyleRenderService service() {
        return new TocStyleRenderService(projectBridgeClient, currentActorService);
    }

    private void stubTagDesign(String htmlTemplate) {
        when(projectBridgeClient.resolveTagDesign(any(), anyString(), any())).thenReturn(
                new ProjectBridgeClient.TagDesignResponse("#fff", "#000", "#f00", null, htmlTemplate));
    }

    @Test
    void applyHtmlTemplate_projectIdがnullでもテンプレート未設定ならHTMLをそのまま返す() {
        stubTagDesign(null);

        String result = service().applyHtmlTemplate(HTML_WITH_TOC, null);

        // 目印(issue #1563)を除けば、投稿時点のHTMLは従来どおり
        assertEquals(HTML_WITH_TOC, result.replaceAll("<!-- /?lbs:embed[^>]*-->\\n", ""));

        verify(projectBridgeClient).resolveTagDesign(isNull(), anyString(), any());
    }

    @Test
    void applyHtmlTemplate_projectIdがnullでもデフォルトのテンプレートがあれば適用する() {
        stubTagDesign("<div class=\"custom\">{{toc}}</div>");

        String result = service().applyHtmlTemplate(HTML_WITH_TOC, null);

        assertTrue(result.contains("<div class=\"custom\"><ul class=\"lb-toc-list\">"));
        assertTrue(result.contains("</ul></div>"));
    }

    @Test
    void applyHtmlTemplate_projectId指定ありは従来どおりテンプレートを適用する() {
        stubTagDesign("<div class=\"custom\">{{toc}}</div>");

        String result = service().applyHtmlTemplate(HTML_WITH_TOC, 1L);

        assertTrue(result.contains("<div class=\"custom\">"));
        verify(projectBridgeClient).resolveTagDesign(org.mockito.ArgumentMatchers.eq(1L), anyString(), any());
    }

    // ---- issue #1563: 見出しの構造を目印として残す ----

    private static final String NESTED_TOC =
            "<ul class=\"lb-toc-list\"><li><a href=\"#a\">A &amp; <code>x</code></a><ul><li><a href=\"#b\">B</a></li>"
                    + "<li><a href=\"#c\">C</a></li></ul></li><li><a href=\"#d\">D</a></li></ul>\n<h2 id=\"a\">A</h2>";

    @Test
    void applyHtmlTemplate_見出しの入れ子の構造を目印に残し_投稿時点のHTMLを目印で挟む() {
        stubTagDesign("<div class=\"custom\">{{toc}}</div>");

        String result = service().applyHtmlTemplate(NESTED_TOC, 1L);

        com.fasterxml.jackson.databind.JsonNode marker = EmbedMarkerTestSupport.firstMarker(result);
        assertEquals("TOC", marker.get("type").asText());
        com.fasterxml.jackson.databind.JsonNode items = marker.get("data").get("items");
        assertEquals(2, items.size());
        assertEquals("A & x", items.get(0).get("text").asText());
        assertEquals("#a", items.get(0).get("href").asText());
        assertEquals(2, items.get(0).get("children").size());
        assertEquals("B", items.get(0).get("children").get(0).get("text").asText());
        assertEquals("#c", items.get(0).get("children").get(1).get("href").asText());
        assertEquals(0, items.get(1).get("children").size());
        assertEquals(1, EmbedMarkerTestSupport.closeCount(result));
        assertTrue(result.indexOf("<!-- lbs:embed ") < result.indexOf("<div class=\"custom\">"));
        assertTrue(result.indexOf("</ul></div>") < result.indexOf("<!-- /lbs:embed -->"));
        assertTrue(result.endsWith("<h2 id=\"a\">A</h2>"));
    }

    @Test
    void applyHtmlTemplate_テンプレートが無くても目印を付ける() {
        stubTagDesign(null);

        String result = service().applyHtmlTemplate(HTML_WITH_TOC, 1L);

        assertEquals("TOC", EmbedMarkerTestSupport.firstMarker(result).get("type").asText());
        assertTrue(result.contains(HTML_WITH_TOC.substring(0, HTML_WITH_TOC.indexOf("\n"))));
    }

    @Test
    void applyHtmlTemplate_目印のJSONはコメントを閉じずタグ記法として解釈されない() {
        stubTagDesign(null);
        String html = "<ul class=\"lb-toc-list\"><li><a href=\"#a\">A --&gt; &lt;b&gt;[toc]&lt;/b&gt;</a></li></ul>";

        String result = service().applyHtmlTemplate(html, 1L);

        String raw = EmbedMarkerTestSupport.firstMarkerRaw(result);
        // 配列の[ ]はJSONの構造なので残る。文字列の中の記号だけがエスケープされる(目次の目印はHTML変換後に付くため問題ない)
        assertFalse(raw.contains("-") || raw.contains("<") || raw.contains(">"));
        assertFalse(raw.contains("toc]") || raw.contains("[toc"));
        assertEquals("A --> <b>[toc]</b>",
                EmbedMarkerTestSupport.firstMarker(result).get("data").get("items").get(0).get("text").asText());
    }

    @Test
    void applyHtmlTemplate_構造を読み取れない目次には目印を付けず_投稿時点のHTMLのまま返す() {
        stubTagDesign(null);
        String broken = "<ul class=\"lb-toc-list\"><ul></ul></ul>";
        String noItem = "<ul class=\"lb-toc-list\"><li></li></ul>";
        String strayClose = "<ul class=\"lb-toc-list\"></li></ul>";
        String noList = "<ul class=\"lb-toc-list\"><a href=\"#a\">A</a></ul>";

        assertEquals(broken, service().applyHtmlTemplate(broken, 1L));
        assertEquals(noItem, service().applyHtmlTemplate(noItem, 1L));
        assertEquals(strayClose, service().applyHtmlTemplate(strayClose, 1L));
        assertEquals(noList, service().applyHtmlTemplate(noList, 1L));
    }

    @Test
    void applyHtmlTemplate_目次が無ければ目印を付けない() {
        stubTagDesign("<div>{{toc}}</div>");

        assertEquals("<p>目次なし</p>", service().applyHtmlTemplate("<p>目次なし</p>", 1L));
    }

    @Test
    void applyHtmlTemplate_実際のflexmarkの目次から入れ子の構造を読み取れる() {
        stubTagDesign(null);
        String html = new com.letsblog.content.markdown.MarkdownRenderer()
                .render("[toc]\n\n## 第一章\n\n### 節 <1>\n\n## 第二章\n");

        String result = service().applyHtmlTemplate(html, 1L);

        com.fasterxml.jackson.databind.JsonNode items = EmbedMarkerTestSupport.firstMarker(result).get("data").get("items");
        assertEquals(2, items.size());
        assertEquals("第一章", items.get(0).get("text").asText());
        assertEquals("節 <1>", items.get(0).get("children").get(0).get("text").asText());
        assertEquals("第二章", items.get(1).get("text").asText());
    }
}
