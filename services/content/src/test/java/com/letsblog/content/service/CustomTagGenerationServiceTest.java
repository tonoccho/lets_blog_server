package com.letsblog.content.service;

import com.letsblog.content.domain.CustomTag;
import com.letsblog.content.dto.GenerateCustomTagRequest;
import com.letsblog.content.dto.GenerateCustomTagResponse;
import com.letsblog.content.testsupport.ContentWorld;
import com.letsblog.content.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.letsblog.content.client.AiServiceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #656: #641のレビューで、CustomTag*サービス群のうちCustomTagGenerationServiceが未テストの
 * まま残っていたことを受けて追加。LLMレスポンスからのHTML/CSS抽出・バリデーション連携・重複チェックを検証する(Penpotデザインファイル連携は#1750で削除)。
 *
 * <p>issue #1821: AiGenerationClient・AdminAuthorizationService・CustomTagValidationServiceは実物。
 * ai-service(LLM)・identity-serviceはHTTPフェイク、リポジトリ(DB)だけが差し替え。
 */
class CustomTagGenerationServiceTest {

    private static final String GENERATE_PATH = "/api/internal/ai/generate";

    private ContentWorld world;
    private CustomTagGenerationService service;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
        service = world.customTagGenerationService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private GenerateCustomTagRequest buildRequest() {
        return new GenerateCustomTagRequest("シンプルな注意書きカードを作って", "note", "desc", null);
    }

    private String llmResponse(String html, String css) {
        return "```html\n" + html + "\n```\n\n```css\n" + css + "\n```";
    }

    /** ai-serviceのLLMが、指定の文章を生成結果として返す。 */
    private void llmReturns(String text) {
        world.http.json("POST", GENERATE_PATH, world.toJson(java.util.Map.of("result", text)));
    }

    // ---- issue #1409: 保存せずに生成内容だけを返す(非同期ジョブ用) ----

    @Test
    void generateContent_HTMLとCSSを返し保存も管理者確認もしない() {
        String html = "<div class=\"note\">{{content}}</div>";
        String css = ".note { color: red; }";
        llmReturns(llmResponse(html, css));
        world.actingAsUser(2L);

        CustomTagGenerationService.GeneratedContent content = service.generateContent("注意書きカード");

        assertEquals(html, content.htmlTemplate());
        assertEquals(css, content.cssContent());
        assertTrue(world.savedTags.isEmpty());
        // 管理者の確認はリクエスト側(受理側)の仕事。ここではidentity-serviceを呼ばない
        assertTrue(world.http.requests("GET", "/api/identity/me").isEmpty());
        // プロンプトは要件つきでai-serviceへ送られる
        var sent = world.bodyOf(world.http.requests("POST", GENERATE_PATH).get(0));
        assertTrue(sent.get("prompt").asText().contains("注意書きカード"));
        assertTrue(sent.get("projectId").isNull());
    }

    @Test
    void generateContent_CSSが無ければ空文字で返す() {
        llmReturns("```html\n<p>{{content}}</p>\n```");

        assertEquals("", service.generateContent("p").cssContent());
    }

    @Test
    void generateContent_HTMLを抽出できなければ例外() {
        llmReturns("説明だけ");

        assertThrows(InvalidCustomTagContentException.class, () -> service.generateContent("p"));
    }

    @Test
    void generateContent_検証に落ちたら理由つきの例外() {
        llmReturns("```html\n<script>x</script>\n```");

        InvalidCustomTagContentException e =
                assertThrows(InvalidCustomTagContentException.class, () -> service.generateContent("p"));
        assertTrue(e.getMessage().contains("<script>タグは許可されていません"));
    }

    @Test
    void generateContent_ai_serviceが失敗すればAiServiceException() {
        world.http.status("POST", GENERATE_PATH, 400, "{\"error\":\"bad\"}");

        assertThrows(AiServiceException.class, () -> service.generateContent("p"));
    }

    @Test
    void generateContent_ai_serviceの応答が空ならAiServiceException() {
        world.http.on("POST", GENERATE_PATH, FakeHttpServer.Response.empty(200));

        AiServiceException e = assertThrows(AiServiceException.class, () -> service.generateContent("p"));
        assertTrue(e.getMessage().contains("空の応答"));
    }

    @Test
    void generate_LLMレスポンスからHTMLとCSSを抽出しバリデーションを通れば保存する() {
        String html = "<div class=\"note\">{{content}}</div>";
        String css = ".note { color: red; }";
        llmReturns(llmResponse(html, css));

        GenerateCustomTagResponse response = service.generate(buildRequest());

        assertEquals(1L, response.id());
        assertEquals("note", response.tagName());
        assertEquals(html, response.htmlTemplate());
        assertEquals(css, response.cssContent());
        assertEquals("Bearer token-1",
                world.http.requests("POST", GENERATE_PATH).get(0).header("Authorization"));
    }

    @Test
    void generate_プロジェクトを指定すればそのプロジェクトのタグとして保存し_同名でも別プロジェクトなら重複にしない() {
        llmReturns(llmResponse("<div>{{content}}</div>", ""));
        world.tag("note", "<p></p>", null, com.letsblog.content.domain.CustomTagFormat.BLOCK, null);

        GenerateCustomTagResponse response =
                service.generate(new GenerateCustomTagRequest("p", "note", "desc", 5L));

        assertEquals(5L, response.projectId());
    }

    @Test
    void generate_HTMLが抽出できない場合はInvalidCustomTagContentException() {
        llmReturns("説明のみでコードブロックなし");

        assertThrows(InvalidCustomTagContentException.class, () -> service.generate(buildRequest()));
        assertTrue(world.savedTags.isEmpty());
    }

    @Test
    void generate_バリデーション失敗時はInvalidCustomTagContentExceptionでエラー内容を含む() {
        llmReturns(llmResponse("<div>{{content}}<script>alert(1)</script></div>", ""));

        InvalidCustomTagContentException exception =
                assertThrows(InvalidCustomTagContentException.class, () -> service.generate(buildRequest()));

        assertTrue(exception.getMessage().contains("<script>タグは許可されていません"));
        assertTrue(world.savedTags.isEmpty());
    }

    @Test
    void generate_タグ名が既に存在する場合はIllegalArgumentExceptionで保存されない() {
        llmReturns(llmResponse("<div class=\"note\">{{content}}</div>", ".note { color: red; }"));
        world.tag("note", "<p></p>", null, com.letsblog.content.domain.CustomTagFormat.BLOCK, null);

        assertThrows(IllegalArgumentException.class, () -> service.generate(buildRequest()));
        assertEquals(1, world.savedTags.size());
    }

    @Test
    void generate_プロジェクト内でタグ名が既に存在する場合もIllegalArgumentException() {
        llmReturns(llmResponse("<div>{{content}}</div>", ""));
        world.tag("note", "<p></p>", null, com.letsblog.content.domain.CustomTagFormat.BLOCK, 5L);

        assertThrows(IllegalArgumentException.class,
                () -> service.generate(new GenerateCustomTagRequest("p", "note", "desc", 5L)));
    }

    @Test
    void generate_admin権限が無ければForbiddenExceptionが伝播しLLM呼び出しは行われない() {
        world.actingAsUser(2L);

        assertThrows(ForbiddenException.class, () -> service.generate(buildRequest()));
        assertTrue(world.http.requests("POST", GENERATE_PATH).isEmpty());
    }
}
