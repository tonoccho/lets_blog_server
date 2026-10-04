package com.letsblog.content.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.content.client.ProjectBridgeClient;
import com.letsblog.content.client.ProjectBridgeClient.TagDesignResponse;
import com.letsblog.content.domain.CustomTagFormat;
import com.letsblog.content.dto.CustomTagResponse;
import com.letsblog.content.dto.LetsblogSyncPayloadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * WordPress プラグインへ送る内容(タグ定義・統合CSS・プレフィックス・組み込みタグのデザイン)の組み立てと、
 * その内容のハッシュ(issue #1558)。ハッシュはプラグインの status と照合する。
 */
@ExtendWith(MockitoExtension.class)
class LetsblogSyncPayloadServiceTest {

    private static final Long PROJECT_ID = 5L;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private CustomTagService customTagService;
    @Mock
    private ProjectContentSettingsService projectContentSettingsService;
    @Mock
    private ProjectBridgeClient projectBridgeClient;
    @Mock
    private CurrentActorService currentActorService;

    private LetsblogSyncPayloadService service;

    @BeforeEach
    void setUp() {
        service = new LetsblogSyncPayloadService(
                customTagService, projectContentSettingsService, projectBridgeClient, currentActorService, objectMapper);
        lenient().when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer t");
        lenient().when(projectContentSettingsService.resolveCssSelectorPrefix(PROJECT_ID)).thenReturn("demo");
        lenient().when(customTagService.buildCssBundle(PROJECT_ID)).thenReturn(".demo .a{color:red}");
        lenient().when(customTagService.list(PROJECT_ID)).thenReturn(List.of(
                tag(2L, "zeta", PROJECT_ID, "<z/>", ".z{}"),
                tag(1L, "alpha", null, "<a/>", null),
                tag(3L, "beta", PROJECT_ID, "<b/>", ".b{}")));
        lenient().when(projectBridgeClient.resolveTagDesign(eq(PROJECT_ID), org.mockito.ArgumentMatchers.anyString(), eq("Bearer t")))
                .thenAnswer(inv -> new TagDesignResponse("#fff", "#000", "#f00", "/*c*/", "<t " + inv.getArgument(1) + "/>"));
        lenient().when(projectBridgeClient.toColors(org.mockito.ArgumentMatchers.any())).thenCallRealMethod();
    }

    private CustomTagResponse tag(Long id, String name, Long projectId, String html, String css) {
        return new CustomTagResponse(id, name, html, "desc", css, CustomTagFormat.BLOCK, projectId, null, null, null);
    }

    private JsonNode payloadJson(LetsblogSyncPayloadResponse response) throws Exception {
        return objectMapper.readTree(response.payload());
    }

    private String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 統合CSSとプレフィックスを含む() throws Exception {
        JsonNode json = payloadJson(service.build(PROJECT_ID));

        assertEquals("demo", json.get("cssSelectorPrefix").asText());
        assertEquals(".demo .a{color:red}", json.get("cssBundle").asText());
        assertEquals(PROJECT_ID, json.get("projectId").asLong());
    }

    @Test
    void プロジェクトのタグとグローバルタグをスコープ付きで名前順に含む() throws Exception {
        JsonNode tags = payloadJson(service.build(PROJECT_ID)).get("customTags");

        assertEquals(3, tags.size());
        assertEquals("GLOBAL", tags.get(0).get("scope").asText());
        assertEquals("alpha", tags.get(0).get("tagName").asText());
        assertEquals("PROJECT", tags.get(1).get("scope").asText());
        assertEquals("beta", tags.get(1).get("tagName").asText());
        assertEquals("zeta", tags.get(2).get("tagName").asText());
        assertEquals("<b/>", tags.get(1).get("htmlTemplate").asText());
        assertEquals(".b{}", tags.get(1).get("cssContent").asText());
        assertEquals("BLOCK", tags.get(1).get("tagFormat").asText());
    }

    @Test
    void 組み込みタグのデザインテンプレートを全種類含む() throws Exception {
        JsonNode designs = payloadJson(service.build(PROJECT_ID)).get("tagDesigns");

        assertEquals(3, designs.size());
        assertEquals("TOC", designs.get(0).get("tagType").asText());
        assertEquals("BLOGCARD", designs.get(1).get("tagType").asText());
        assertEquals("AMAZON", designs.get(2).get("tagType").asText());
        assertEquals("<t TOC/>", designs.get(0).get("htmlTemplate").asText());
        assertEquals("#fff", designs.get(0).get("backgroundColor").asText());
        assertEquals("/*c*/", designs.get(0).get("customCss").asText());
    }

    @Test
    void ハッシュは送る内容そのもののSHA256() throws Exception {
        LetsblogSyncPayloadResponse response = service.build(PROJECT_ID);

        assertEquals(sha256(response.payload()), response.hash());
        assertEquals(64, response.hash().length());
    }

    @Test
    void 同じ入力なら同じ内容とハッシュになりタグの並びに依らない() throws Exception {
        LetsblogSyncPayloadResponse first = service.build(PROJECT_ID);
        when(customTagService.list(PROJECT_ID)).thenReturn(List.of(
                tag(3L, "beta", PROJECT_ID, "<b/>", ".b{}"),
                tag(1L, "alpha", null, "<a/>", null),
                tag(2L, "zeta", PROJECT_ID, "<z/>", ".z{}")));

        LetsblogSyncPayloadResponse second = service.build(PROJECT_ID);

        assertEquals(first.payload(), second.payload());
        assertEquals(first.hash(), second.hash());
    }

    @Test
    void タグを変えるとハッシュが変わる() {
        String before = service.build(PROJECT_ID).hash();
        when(customTagService.list(PROJECT_ID)).thenReturn(List.of(tag(9L, "new-tag", PROJECT_ID, "<n/>", ".n{}")));

        assertNotEquals(before, service.build(PROJECT_ID).hash());
    }

    @Test
    void プレフィックスやCSSを変えるとハッシュが変わる() {
        String before = service.build(PROJECT_ID).hash();
        when(projectContentSettingsService.resolveCssSelectorPrefix(PROJECT_ID)).thenReturn("other");
        String afterPrefix = service.build(PROJECT_ID).hash();
        when(customTagService.buildCssBundle(PROJECT_ID)).thenReturn(".other .a{color:blue}");
        String afterCss = service.build(PROJECT_ID).hash();

        assertNotEquals(before, afterPrefix);
        assertNotEquals(afterPrefix, afterCss);
    }

    @Test
    void 日本語や特殊文字もそのまま含みJSONとして読み戻せる() throws Exception {
        when(customTagService.list(PROJECT_ID)).thenReturn(List.of(tag(1L, "吹き出し", PROJECT_ID, "<p class=\"x\">\"引用\"</p>", ".x{content:'\\201C'}")));

        JsonNode tags = payloadJson(service.build(PROJECT_ID)).get("customTags");

        assertEquals("吹き出し", tags.get(0).get("tagName").asText());
        assertTrue(tags.get(0).get("htmlTemplate").asText().contains("\"引用\""));
    }
}
