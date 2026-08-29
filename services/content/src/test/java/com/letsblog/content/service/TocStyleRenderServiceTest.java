package com.letsblog.content.service;

import com.letsblog.content.client.LegacyApiBridgeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private LegacyApiBridgeClient legacyApiBridgeClient;

    @Mock
    private CurrentActorService currentActorService;

    private TocStyleRenderService service() {
        return new TocStyleRenderService(legacyApiBridgeClient, currentActorService);
    }

    private void stubTagDesign(String htmlTemplate) {
        when(legacyApiBridgeClient.resolveTagDesign(any(), anyString(), any())).thenReturn(
                new LegacyApiBridgeClient.TagDesignResponse("#fff", "#000", "#f00", null, htmlTemplate));
    }

    @Test
    void applyHtmlTemplate_projectIdがnullでもテンプレート未設定ならHTMLをそのまま返す() {
        stubTagDesign(null);

        assertEquals(HTML_WITH_TOC, service().applyHtmlTemplate(HTML_WITH_TOC, null));

        verify(legacyApiBridgeClient).resolveTagDesign(isNull(), anyString(), any());
    }

    @Test
    void applyHtmlTemplate_projectIdがnullでもデフォルトのテンプレートがあれば適用する() {
        stubTagDesign("<div class=\"custom\">{{toc}}</div>");

        String result = service().applyHtmlTemplate(HTML_WITH_TOC, null);

        assertTrue(result.startsWith("<div class=\"custom\"><ul class=\"lb-toc-list\">"));
        assertTrue(result.contains("</ul></div>"));
    }

    @Test
    void applyHtmlTemplate_projectId指定ありは従来どおりテンプレートを適用する() {
        stubTagDesign("<div class=\"custom\">{{toc}}</div>");

        String result = service().applyHtmlTemplate(HTML_WITH_TOC, 1L);

        assertTrue(result.startsWith("<div class=\"custom\">"));
        verify(legacyApiBridgeClient).resolveTagDesign(org.mockito.ArgumentMatchers.eq(1L), anyString(), any());
    }
}
