package com.letsblog.content.service;

import com.letsblog.content.domain.CustomTag;
import com.letsblog.content.domain.CustomTagFormat;
import com.letsblog.content.markdown.MarkdownRenderer;
import com.letsblog.content.repository.CustomTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * issue #641: CustomTagRenderServiceは管理画面で登録されたHTMLテンプレートをMarkdown本文中の
 * ショートコードに展開する、公開パイプラインの中核。BLOCK/INLINE形式の判別・未登録タグの
 * 素通し・属性プレースホルダー展開を検証する。
 */
@ExtendWith(MockitoExtension.class)
class CustomTagRenderServiceTest {

    @Mock
    private CustomTagRepository customTagRepository;

    private CustomTagRenderService service;

    @BeforeEach
    void setUp() {
        service = new CustomTagRenderService(customTagRepository, new MarkdownRenderer());
    }

    private CustomTag buildTag(String name, CustomTagFormat format, String htmlTemplate) {
        CustomTag tag = new CustomTag();
        tag.setTagName(name);
        tag.setTagFormat(format);
        tag.setHtmlTemplate(htmlTemplate);
        return tag;
    }

    @Test
    void render_markdownがnullまたは空ならそのまま返す() {
        assertEquals(null, service.render(null));
        assertEquals("", service.render(""));
    }

    @Test
    void render_登録済みタグが無い場合は本文をそのまま返す() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(List.of());

        String result = service.render("[unknown]content[/unknown]");

        assertEquals("[unknown]content[/unknown]", result);
    }

    @Test
    void render_BLOCK形式のタグをテンプレートへ展開する() {
        when(customTagRepository.findByProjectIdIsNull())
                .thenReturn(List.of(buildTag("note", CustomTagFormat.BLOCK, "<div class=\"note\">{{content}}</div>")));

        String result = service.render("前置き\n[note]\n注意事項です\n[/note]\n後書き");

        assertTrue(result.contains("<div class=\"note\">"));
        assertTrue(result.contains("注意事項です"));
        assertTrue(result.contains("前置き"));
        assertTrue(result.contains("後書き"));
    }

    @Test
    void render_INLINE形式のタグをテンプレートへ展開する() {
        when(customTagRepository.findByProjectIdIsNull())
                .thenReturn(List.of(buildTag("badge", CustomTagFormat.INLINE, "<span class=\"badge\">{{content}}</span>")));

        String result = service.render("文章中に[badge]NEW[/badge]が入る");

        assertEquals("文章中に<span class=\"badge\">NEW</span>が入る", result);
    }

    @Test
    void render_記述形式とタグの登録形式が一致しない場合は展開しない() {
        // "note"はBLOCK形式で登録されているが、本文中ではINLINEの記法(同一行)で書かれている
        when(customTagRepository.findByProjectIdIsNull())
                .thenReturn(List.of(buildTag("note", CustomTagFormat.BLOCK, "<div>{{content}}</div>")));

        String result = service.render("文章中に[note]inline記法[/note]が入る");

        assertEquals("文章中に[note]inline記法[/note]が入る", result);
    }

    @Test
    void render_属性プレースホルダーを実際の属性値へ置換する() {
        when(customTagRepository.findByProjectIdIsNull())
                .thenReturn(List.of(buildTag("alert", CustomTagFormat.INLINE,
                        "<span data-level=\"{{attr:level}}\">{{content}}</span>")));

        String result = service.render("[alert level=\"warning\"]注意[/alert]");

        assertEquals("<span data-level=\"warning\">注意</span>", result);
    }

    @Test
    void render_未指定の属性プレースホルダーは空文字に置換する() {
        when(customTagRepository.findByProjectIdIsNull())
                .thenReturn(List.of(buildTag("alert", CustomTagFormat.INLINE,
                        "<span data-level=\"{{attr:level}}\">{{content}}</span>")));

        String result = service.render("[alert]注意[/alert]");

        assertEquals("<span data-level=\"\">注意</span>", result);
    }

    @Test
    void render_projectId指定時はプロジェクト固有タグとグローバルタグの両方を対象にする() {
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(42L))
                .thenReturn(List.of(buildTag("note", CustomTagFormat.INLINE, "<span>{{content}}</span>")));

        String result = service.render("[note]プロジェクト専用[/note]", 42L);

        assertEquals("<span>プロジェクト専用</span>", result);
    }

    @Test
    void previewTemplate_contentプレースホルダーをMarkdownレンダリング結果へ差し込む() {
        String result = service.previewTemplate("<div>{{content}}</div>", "**太字**のテスト");

        assertTrue(result.contains("<strong>太字</strong>"));
    }
}
