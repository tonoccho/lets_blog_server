package com.letsblog.content.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.content.domain.CustomTagFormat;
import com.letsblog.content.dto.LetsblogSyncPayloadResponse;
import com.letsblog.content.testsupport.BrokenObjectMapper;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WordPress プラグインへ送る内容(タグ定義・統合CSS・プレフィックス・組み込みタグのデザイン)の組み立てと、
 * その内容のハッシュ(issue #1558)。ハッシュはプラグインの status と照合する。
 *
 * <p>issue #1821: CustomTagService・ProjectContentSettingsService・ProjectBridgeClient・CurrentActorServiceは実物。
 * project-service・identity-serviceはHTTPフェイク、リポジトリ(DB)だけが差し替え。
 */
class LetsblogSyncPayloadServiceTest {

    private static final Long PROJECT_ID = 5L;

    private ContentWorld world;
    private LetsblogSyncPayloadService service;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
        service = world.letsblogSyncPayloadService;
        world.settingsFor(PROJECT_ID, "demo");
        for (String type : new String[] {"TOC", "BLOGCARD", "AMAZON"}) {
            world.tagDesign(type, "/*c*/", "<t " + type + "/>");
        }
        world.tag("zeta", "<z/>", ".z{}", CustomTagFormat.BLOCK, PROJECT_ID);
        world.tag("alpha", "<a/>", null, CustomTagFormat.BLOCK, null);
        world.tag("beta", "<b/>", ".b{}", CustomTagFormat.BLOCK, PROJECT_ID);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private JsonNode payloadJson(LetsblogSyncPayloadResponse response) {
        return world.parse(response.payload());
    }

    private String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 統合CSSとプレフィックスを含む() {
        JsonNode json = payloadJson(service.build(PROJECT_ID));

        assertEquals("demo", json.get("cssSelectorPrefix").asText());
        assertEquals(PROJECT_ID, json.get("projectId").asLong());
        String bundle = json.get("cssBundle").asText();
        // 実物の統合CSS: プレフィックス付きのタグCSSと、組み込みタグのデザインCSS(customCssが優先される)
        assertTrue(bundle.contains("/* === zeta === */\n.demo .z {}"), bundle);
        assertTrue(bundle.contains("/* === toc (組み込みタグ) === */\n/*c*/"), bundle);
    }

    @Test
    void プロジェクトのタグとグローバルタグをスコープ付きで名前順に含む() {
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
    void 組み込みタグのデザインテンプレートを全種類含む() {
        JsonNode designs = payloadJson(service.build(PROJECT_ID)).get("tagDesigns");

        assertEquals(3, designs.size());
        assertEquals("TOC", designs.get(0).get("tagType").asText());
        assertEquals("BLOGCARD", designs.get(1).get("tagType").asText());
        assertEquals("AMAZON", designs.get(2).get("tagType").asText());
        assertEquals("<t TOC/>", designs.get(0).get("htmlTemplate").asText());
        assertEquals("#fff", designs.get(0).get("backgroundColor").asText());
        assertEquals("/*c*/", designs.get(0).get("customCss").asText());
        // 呼び出し元のBearerトークンで、プロジェクトのデザインを取得している
        var request = world.http.requests("GET", "/api/internal/project/tag-design/TOC").get(0);
        assertEquals("projectId=5", request.query());
        assertEquals("Bearer token-1", request.header("Authorization"));
    }

    @Test
    void ハッシュは送る内容そのもののSHA256() throws Exception {
        LetsblogSyncPayloadResponse response = service.build(PROJECT_ID);

        assertEquals(sha256(response.payload()), response.hash());
        assertEquals(64, response.hash().length());
    }

    @Test
    void 同じ入力なら同じ内容とハッシュになりタグの並びに依らない() {
        LetsblogSyncPayloadResponse first = service.build(PROJECT_ID);
        try (ContentWorld other = new ContentWorld().actingAsAdmin(1L)) {
            other.settingsFor(PROJECT_ID, "demo");
            for (String type : new String[] {"TOC", "BLOGCARD", "AMAZON"}) {
                other.tagDesign(type, "/*c*/", "<t " + type + "/>");
            }
            // 作成順を変える
            other.tag("beta", "<b/>", ".b{}", CustomTagFormat.BLOCK, PROJECT_ID);
            other.tag("alpha", "<a/>", null, CustomTagFormat.BLOCK, null);
            other.tag("zeta", "<z/>", ".z{}", CustomTagFormat.BLOCK, PROJECT_ID);

            LetsblogSyncPayloadResponse second = other.letsblogSyncPayloadService.build(PROJECT_ID);

            assertEquals(first.payload().substring(first.payload().indexOf("\"customTags\"")),
                    second.payload().substring(second.payload().indexOf("\"customTags\"")));
        }
    }

    @Test
    void タグを変えるとハッシュが変わる() {
        String before = service.build(PROJECT_ID).hash();
        world.tag("new-tag", "<n/>", ".n{}", CustomTagFormat.BLOCK, PROJECT_ID);

        assertNotEquals(before, service.build(PROJECT_ID).hash());
    }

    @Test
    void プレフィックスやCSSを変えるとハッシュが変わる() {
        String before = service.build(PROJECT_ID).hash();
        world.settings.get(PROJECT_ID).setCssSelectorPrefix("other");
        String afterPrefix = service.build(PROJECT_ID).hash();
        world.tags.get(1L).setCssContent(".other .a{color:blue}");
        String afterCss = service.build(PROJECT_ID).hash();

        assertNotEquals(before, afterPrefix);
        assertNotEquals(afterPrefix, afterCss);
    }

    @Test
    void 日本語や特殊文字もそのまま含みJSONとして読み戻せる() {
        world.tag("吹き出し", "<p class=\"x\">\"引用\"</p>", ".x{content:'\\201C'}", CustomTagFormat.BLOCK, PROJECT_ID);

        JsonNode tags = payloadJson(service.build(PROJECT_ID)).get("customTags");

        JsonNode balloon = null;
        for (JsonNode tag : tags) {
            if ("吹き出し".equals(tag.get("tagName").asText())) {
                balloon = tag;
            }
        }
        assertTrue(balloon != null && balloon.get("htmlTemplate").asText().contains("\"引用\""));
    }

    @Test
    void 内容をJSONにできなければ例外() {
        LetsblogSyncPayloadService broken = new LetsblogSyncPayloadService(
                world.customTagService, world.projectContentSettingsService, world.projectBridgeClient,
                world.currentActorService, BrokenObjectMapper.create());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> broken.build(PROJECT_ID));

        assertTrue(e.getMessage().contains("同期内容をJSONにできません"));
    }
}
