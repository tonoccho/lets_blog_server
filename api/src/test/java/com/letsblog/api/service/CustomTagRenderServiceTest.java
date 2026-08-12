package com.letsblog.api.service;

import com.letsblog.api.domain.CustomTag;
import com.letsblog.api.domain.CustomTagFormat;
import com.letsblog.api.markdown.MarkdownRenderer;
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
        service = new CustomTagRenderService(customTagRepository, new MarkdownRenderer());
    }

    private CustomTag tag(String name, String template) {
        return tag(name, template, CustomTagFormat.BLOCK);
    }

    private CustomTag tag(String name, String template, CustomTagFormat format) {
        CustomTag tag = new CustomTag();
        tag.setTagName(name);
        tag.setHtmlTemplate(template);
        tag.setTagFormat(format);
        return tag;
    }

    @Test
    void render_ブロック形式のタグを本文込みでHTMLテンプレートに展開する() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tag("alert", "<div class=\"alert\">{{content}}</div>")));

        String markdown = "本文\n\n[alert]\n注意してください\n[/alert]\n\n続き";

        String result = service.render(markdown);

        assertEquals("本文\n\n<div class=\"alert\">注意してください</div>\n\n続き", result);
    }

    @Test
    void render_インライン形式のタグを文章中に埋め込んで展開する() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tag("badge", "<span class=\"badge\">{{content}}</span>", CustomTagFormat.INLINE)));

        String markdown = "この文章は[badge]インライン[/badge]の記述例です。";

        String result = service.render(markdown);

        assertEquals("この文章は<span class=\"badge\">インライン</span>の記述例です。", result);
    }

    @Test
    void render_インライン形式のタグは複数行にまたがると展開されない() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tag("badge", "<span class=\"badge\">{{content}}</span>", CustomTagFormat.INLINE)));

        String markdown = "[badge]\n複数行\n[/badge]";

        String result = service.render(markdown);

        assertEquals(markdown, result);
    }

    @Test
    void render_ブロック形式のタグは同一行に閉じタグがあると展開されない() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tag("alert", "<div class=\"alert\">{{content}}</div>", CustomTagFormat.BLOCK)));

        String markdown = "[alert]注意[/alert]";

        String result = service.render(markdown);

        assertEquals(markdown, result);
    }

    @Test
    void render_属性プレースホルダを展開する() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tag("youtube", "<iframe src=\"https://youtube.com/embed/{{attr:id}}\"></iframe>")));

        String markdown = "[youtube id=\"abc123\"]\n\n[/youtube]";

        String result = service.render(markdown);

        assertEquals("<iframe src=\"https://youtube.com/embed/abc123\"></iframe>", result);
    }

    @Test
    void render_インライン形式でも属性プレースホルダを展開する() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tag("link", "<a href=\"{{attr:href}}\">{{content}}</a>", CustomTagFormat.INLINE)));

        String markdown = "詳細は[link href=\"https://example.com\"]こちら[/link]から。";

        String result = service.render(markdown);

        assertEquals("詳細は<a href=\"https://example.com\">こちら</a>から。", result);
    }

    @Test
    void render_未定義のタグ名はそのまま残す() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tag("alert", "<div>{{content}}</div>")));

        String markdown = "[unknown]\n本文\n[/unknown]";

        String result = service.render(markdown);

        assertEquals(markdown, result);
    }

    @Test
    void render_カスタムタグ未登録時はMarkdownをそのまま返す() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(List.of());

        String markdown = "[alert]\n本文\n[/alert]";

        assertEquals(markdown, service.render(markdown));
    }

    @Test
    void render_複数タグが混在しても個別に展開する() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(List.of(
                tag("alert", "<div class=\"alert\">{{content}}</div>"),
                tag("note", "<div class=\"note\">{{content}}</div>")));

        String markdown = "[alert]\n危険\n[/alert]\n本文\n[note]\n補足\n[/note]";

        String result = service.render(markdown);

        assertEquals("<div class=\"alert\">危険</div>\n本文\n<div class=\"note\">補足</div>", result);
    }

    @Test
    void render_ブロックとインラインが混在しても個別に展開する() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(List.of(
                tag("alert", "<div class=\"alert\">{{content}}</div>", CustomTagFormat.BLOCK),
                tag("badge", "<span class=\"badge\">{{content}}</span>", CustomTagFormat.INLINE)));

        String markdown = "[alert]\n危険\n[/alert]\n本文中の[badge]強調[/badge]です。";

        String result = service.render(markdown);

        assertEquals("<div class=\"alert\">危険</div>\n本文中の<span class=\"badge\">強調</span>です。", result);
    }

    @Test
    void render_プロジェクトスコープタグがレンダリングされる() {
        CustomTag projectTag = tag("project-only", "<div class=\"p\">{{content}}</div>");
        projectTag.setProjectId(1L);
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(1L)).thenReturn(List.of(projectTag));

        String markdown = "[project-only]\n本文\n[/project-only]";

        String result = service.render(markdown, 1L);

        assertEquals("<div class=\"p\">本文</div>", result);
    }

    @Test
    void render_プロジェクト指定時もグローバルタグが対象になる() {
        CustomTag globalTag = tag("alert", "<div class=\"alert\">{{content}}</div>");
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(1L)).thenReturn(List.of(globalTag));

        String markdown = "[alert]\n注意\n[/alert]";

        String result = service.render(markdown, 1L);

        assertEquals("<div class=\"alert\">注意</div>", result);
    }

    @Test
    void render_contentのMarkdown記法がHTMLに変換されて展開される() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tag("alert", "<div class=\"alert\">{{content}}</div>")));

        String markdown = "[alert]\n**bold** and *italic*\n[/alert]";

        String result = service.render(markdown);

        assertEquals("<div class=\"alert\"><strong>bold</strong> and <em>italic</em></div>", result);
    }

    @Test
    void render_content内の複数段落は段落タグを維持したまま展開される() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(
                List.of(tag("alert", "<div class=\"alert\">{{content}}</div>")));

        String markdown = "[alert]\n1段落目\n\n2段落目\n[/alert]";

        String result = service.render(markdown);

        assertEquals("<div class=\"alert\"><p>1段落目</p>\n<p>2段落目</p></div>", result);
    }

    @Test
    void render_異なるプロジェクトのタグは対象外() {
        // プロジェクト2のタグはリポジトリ検索条件(findByProjectIdOrProjectIdIsNull(1L))に含まれないため、
        // クエリ自体が呼び出し対象外タグを返さないことを想定してスタブする。
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(1L)).thenReturn(List.of());

        String markdown = "[project-two-only]\n本文\n[/project-two-only]";

        String result = service.render(markdown, 1L);

        assertEquals(markdown, result);
    }
}
