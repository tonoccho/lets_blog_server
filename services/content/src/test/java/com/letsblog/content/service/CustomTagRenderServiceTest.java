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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern MARKER = Pattern.compile("<!-- lbs:tag ([^>]*?) -->");

    /** 結果の最初の目印コメントの JSON。 */
    private static JsonNode marker(String result) {
        Matcher m = MARKER.matcher(result);
        assertTrue(m.find(), "目印コメントが無い: " + result);
        try {
            return MAPPER.readTree(m.group(1));
        } catch (Exception e) {
            throw new AssertionError("目印の JSON が読めない: " + m.group(1), e);
        }
    }

    /** 目印の開始コメント + 展開 HTML + 終了コメント(BLOCK は前後に改行を挟む)。result から開始コメントだけ取り出して組み立てる。 */
    private static String wrapped(String result, String expanded) {
        Matcher m = MARKER.matcher(result);
        assertTrue(m.find(), "目印コメントが無い: " + result);
        return m.group(0) + expanded + "<!-- /lbs:tag -->";
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

        assertEquals("文章中に" + wrapped(result, "<span class=\"badge\">NEW</span>") + "が入る", result);
        assertEquals("badge", marker(result).get("name").asText());
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

        assertEquals(wrapped(result, "<span data-level=\"warning\">注意</span>"), result);
        assertEquals("warning", marker(result).get("attrs").get("level").asText());
        assertEquals("注意", marker(result).get("content").asText());
    }

    @Test
    void render_未指定の属性プレースホルダーは空文字に置換する() {
        when(customTagRepository.findByProjectIdIsNull())
                .thenReturn(List.of(buildTag("alert", CustomTagFormat.INLINE,
                        "<span data-level=\"{{attr:level}}\">{{content}}</span>")));

        String result = service.render("[alert]注意[/alert]");

        assertEquals(wrapped(result, "<span data-level=\"\">注意</span>"), result);
        assertEquals(0, marker(result).get("attrs").size());
    }

    @Test
    void render_projectId指定時はプロジェクト固有タグとグローバルタグの両方を対象にする() {
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(42L))
                .thenReturn(List.of(buildTag("note", CustomTagFormat.INLINE, "<span>{{content}}</span>")));

        String result = service.render("[note]プロジェクト専用[/note]", 42L);

        assertEquals(wrapped(result, "<span>プロジェクト専用</span>"), result);
    }

    @Test
    void previewTemplate_contentプレースホルダーをMarkdownレンダリング結果へ差し込む() {
        String result = service.previewTemplate("<div>{{content}}</div>", "**太字**のテスト");

        assertTrue(result.contains("<strong>太字</strong>"));
    }

    // ---- issue #1560: 目印(HTML コメント)付きで出力する ----

    @Test
    void render_BLOCKは目印の中に名前_属性_変換済み本文と投稿時点の展開HTMLを持つ() {
        when(customTagRepository.findByProjectIdIsNull())
                .thenReturn(List.of(buildTag("box", CustomTagFormat.BLOCK,
                        "<div class=\"box\" data-lv=\"{{attr:level}}\">{{content}}</div>")));

        String result = service.render("前\n[box level=\"warn\"]\n**太字**です\n[/box]\n後");

        JsonNode json = marker(result);
        assertEquals("box", json.get("name").asText());
        assertEquals("warn", json.get("attrs").get("level").asText());
        assertEquals("<strong>太字</strong>です", json.get("content").asText());
        assertTrue(result.contains("<div class=\"box\" data-lv=\"warn\"><strong>太字</strong>です</div>"));
        assertTrue(result.indexOf("<!-- lbs:tag ") < result.indexOf("<div class=\"box\""));
        assertTrue(result.indexOf("<div class=\"box\"") < result.indexOf("<!-- /lbs:tag -->"));
        assertTrue(result.startsWith("前\n") && result.endsWith("\n後"));
    }

    @Test
    void render_目印のJSONはコメントや後続の展開を壊す文字を含まない() {
        when(customTagRepository.findByProjectIdIsNull())
                .thenReturn(List.of(buildTag("box", CustomTagFormat.BLOCK, "<div>{{content}}</div>")));

        String result = service.render("[box x=\"a--b\"]\n--> <script>[blogcard url=\"u\"]</script> -- [/box]");

        Matcher m = MARKER.matcher(result);
        assertTrue(m.find());
        String body = m.group(1);
        assertTrue(!body.contains("<") && !body.contains(">") && !body.contains("[") && !body.contains("]") && !body.contains("--"),
                body);
        JsonNode json = marker(result);
        assertEquals("a--b", json.get("attrs").get("x").asText());
        assertTrue(json.get("content").asText().contains("[blogcard url="));
        assertEquals(1, result.split("<!-- /lbs:tag -->", -1).length - 1);
    }

    @Test
    void render_目印付きの出力をMarkdown変換しても目印と展開HTMLが残る() {
        when(customTagRepository.findByProjectIdIsNull())
                .thenReturn(List.of(
                        buildTag("box", CustomTagFormat.BLOCK, "<div class=\"box\">{{content}}</div>"),
                        buildTag("badge", CustomTagFormat.INLINE, "<span class=\"b\">{{content}}</span>")));

        String block = new MarkdownRenderer().render(service.render("段落\n\n[box]\n中身\n[/box]\n\n文[badge]N[/badge]文"));

        assertTrue(block.contains("<!-- lbs:tag "), block);
        assertTrue(block.contains("<div class=\"box\">中身</div>"), block);
        assertTrue(block.contains("<span class=\"b\">N</span><!-- /lbs:tag -->"), block);
        assertEquals(2, block.split("<!-- /lbs:tag -->", -1).length - 1);
    }

    @Test
    void render_未登録タグと形式不一致のタグには目印を付けない() {
        when(customTagRepository.findByProjectIdIsNull())
                .thenReturn(List.of(buildTag("note", CustomTagFormat.BLOCK, "<div>{{content}}</div>")));

        String result = service.render("[none]x[/none] と [note]inline[/note]");

        assertEquals("[none]x[/none] と [note]inline[/note]", result);
    }

    @Test
    void render_入れ子のカスタムタグは外側の目印のcontentにも展開済みの目印として入る() {
        when(customTagRepository.findByProjectIdIsNull())
                .thenReturn(List.of(
                        buildTag("box", CustomTagFormat.BLOCK, "<div class=\"box\">{{content}}</div>"),
                        buildTag("badge", CustomTagFormat.INLINE, "<span class=\"badge\">{{content}}</span>")));

        String result = service.render("[box]\n本文[badge]NEW[/badge]です\n[/box]");

        String content = marker(result).get("content").asText();
        // 外側のcontentに生の[badge]が残ると、プラグインの展開し直しで生のタグが画面に出る(#1560レビュー)
        assertTrue(!content.contains("[badge]"), content);
        assertTrue(content.contains("<!-- lbs:tag "), content);
        assertTrue(content.contains("<span class=\"badge\">NEW</span>"), content);
        assertTrue(content.contains("<!-- /lbs:tag -->"), content);
        assertTrue(!result.contains("[badge]"), result);
    }
}
