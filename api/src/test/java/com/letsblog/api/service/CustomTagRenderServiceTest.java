package com.letsblog.api.service;

import com.letsblog.api.domain.CustomTag;
import com.letsblog.api.repository.CustomTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomTagRenderServiceTest {

    @Mock
    private CustomTagRepository customTagRepository;

    private CustomTagRenderService service;

    @BeforeEach
    void setUp() {
        service = new CustomTagRenderService(customTagRepository);
    }

    private CustomTag tag(String name, String template) {
        CustomTag tag = new CustomTag();
        tag.setTagName(name);
        tag.setHtmlTemplate(template);
        return tag;
    }

    private CustomTag tagWithCss(String name, String template, String css) {
        CustomTag tag = tag(name, template);
        tag.setCssContent(css);
        return tag;
    }

    @Test
    void render_定義済みタグを本文込みでHTMLテンプレートに展開する() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tag("alert", "<div class=\"alert\">{{content}}</div>")));

        String markdown = "本文\n\n:::alert\n注意してください\n:::\n\n続き";

        String result = service.render(markdown);

        assertEquals("本文\n\n<div class=\"alert\">注意してください</div>\n\n続き", result);
    }

    @Test
    void render_属性プレースホルダを展開する() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tag("youtube", "<iframe src=\"https://youtube.com/embed/{{attr:id}}\"></iframe>")));

        String markdown = ":::youtube id=\"abc123\"\n\n:::";

        String result = service.render(markdown);

        assertEquals("<iframe src=\"https://youtube.com/embed/abc123\"></iframe>", result);
    }

    @Test
    void render_未定義のタグ名はそのまま残す() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tag("alert", "<div>{{content}}</div>")));

        String markdown = ":::unknown\n本文\n:::";

        String result = service.render(markdown);

        assertEquals(markdown, result);
    }

    @Test
    void render_カスタムタグ未登録時はMarkdownをそのまま返す() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(List.of());

        String markdown = ":::alert\n本文\n:::";

        assertEquals(markdown, service.render(markdown));
    }

    @Test
    void render_複数タグが混在しても個別に展開する() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(List.of(
                tag("alert", "<div class=\"alert\">{{content}}</div>"),
                tag("note", "<div class=\"note\">{{content}}</div>")));

        String markdown = ":::alert\n危険\n:::\n本文\n:::note\n補足\n:::";

        String result = service.render(markdown);

        assertEquals("<div class=\"alert\">危険</div>\n本文\n<div class=\"note\">補足</div>", result);
    }

    @Test
    void render_CSS付きタグを使うと本文冒頭にstyleブロックを差し込む() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tagWithCss("alert", "<div class=\"alert\">{{content}}</div>", ".alert { color: red; }")));

        String markdown = ":::alert\n注意\n:::";

        String result = service.render(markdown);

        assertEquals(
                "<style>\n.alert { color: red; }\n</style>\n\n<div class=\"alert\">注意</div>",
                result);
    }

    @Test
    void render_同じCSS付きタグを複数回使ってもstyleブロックは1回だけ() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tagWithCss("alert", "<div class=\"alert\">{{content}}</div>", ".alert { color: red; }")));

        String markdown = ":::alert\n注意1\n:::\n本文\n:::alert\n注意2\n:::";

        String result = service.render(markdown);

        long styleCount = result.split("<style>", -1).length - 1;
        assertEquals(1, styleCount);
    }

    @Test
    void render_CSSが未設定のタグではstyleブロックを出力しない() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tag("alert", "<div class=\"alert\">{{content}}</div>")));

        String markdown = ":::alert\n注意\n:::";

        String result = service.render(markdown);

        assertEquals("<div class=\"alert\">注意</div>", result);
    }

    @Test
    void render_プロジェクトスコープタグがレンダリングされる() {
        CustomTag projectTag = tag("project-only", "<div class=\"p\">{{content}}</div>");
        projectTag.setProjectId(1L);
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(1L)).thenReturn(List.of(projectTag));

        String markdown = ":::project-only\n本文\n:::";

        String result = service.render(markdown, 1L);

        assertEquals("<div class=\"p\">本文</div>", result);
    }

    @Test
    void render_プロジェクト指定時もグローバルタグが対象になる() {
        CustomTag globalTag = tag("alert", "<div class=\"alert\">{{content}}</div>");
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(1L)).thenReturn(List.of(globalTag));

        String markdown = ":::alert\n注意\n:::";

        String result = service.render(markdown, 1L);

        assertEquals("<div class=\"alert\">注意</div>", result);
    }

    @Test
    void render_異なるプロジェクトのタグは対象外() {
        // プロジェクト2のタグはリポジトリ検索条件(findByProjectIdOrProjectIdIsNull(1L))に含まれないため、
        // クエリ自体が呼び出し対象外タグを返さないことを想定してスタブする。
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(1L)).thenReturn(List.of());

        String markdown = ":::project-two-only\n本文\n:::";

        String result = service.render(markdown, 1L);

        assertEquals(markdown, result);
    }
}
