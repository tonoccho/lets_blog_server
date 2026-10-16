package com.letsblog.project.service;

import com.letsblog.project.cms.ProvisioningResult;
import com.letsblog.project.client.CmsProvisioningBridgeClient;
import com.letsblog.project.testsupport.ProjectWorld;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ProvisioningServiceの回帰テスト(issue #577 stage2、legacy-apiから移設)。
 * 実際のCMS操作はCmsProvisioningBridgeClient経由でpublishing-serviceへ委ねるため、ブリッジ呼び出しの結果を
 * そのままResultへ転写できることを中心に検証する。ブリッジは実物で、HTTPの層だけをフェイクにする(issue #1817)。
 */
class ProvisioningServiceTest {

    private static final String PROVISION = "/api/internal/project/cms/provision";

    private final ProjectWorld world = new ProjectWorld();
    private final CmsProvisioningBridgeClient bridgeClient = world.cmsBridgeClient;

    @AfterEach
    void close() {
        world.close();
    }

    private ProvisioningService service() {
        return new ProvisioningService(bridgeClient);
    }

    @Test
    void provisionSite_ブリッジの結果をそのまま転写する() {
        world.respond("POST", PROVISION, new ProvisioningResult("cat-1", null, "tag-1", null, "author-1", null));

        ProvisioningService.Result result = service().provisionSite(
                "WORDPRESS", Map.of("baseUrl", "https://example.com"), "actor@example.com");

        assertEquals("cat-1", result.defaultCategoryId);
        assertEquals("tag-1", result.defaultTagId);
        assertEquals("author-1", result.authorId);
        var sent = world.bodiesOf("POST", PROVISION).get(0);
        assertEquals("WORDPRESS", sent.get("cmsType").asText());
        assertEquals("actor@example.com", sent.get("actorEmail").asText());
        assertEquals("https://example.com", sent.get("credentials").get("baseUrl").asText());
    }

    @Test
    void provisionSite_部分的失敗はエラー情報を保持したまま返す() {
        world.respond("POST", PROVISION, new ProvisioningResult(null, "カテゴリ作成失敗", "tag-1", null, null, null));

        ProvisioningService.Result result = service().provisionSite("WORDPRESS", Map.of(), null);

        assertEquals("カテゴリ作成失敗", result.categoryError);
        assertEquals("tag-1", result.defaultTagId);
    }

    @Test
    void provisionSite_タグとauthorの失敗も保持しカテゴリ成功はIDを返す() {
        world.respond("POST", PROVISION, new ProvisioningResult("cat-1", null, null, "タグ失敗", null, "著者失敗"));

        ProvisioningService.Result result = service().provisionSite("WORDPRESS", Map.of(), null);

        assertEquals("cat-1", result.defaultCategoryId);
        assertEquals("タグ失敗", result.tagError);
        assertEquals("著者失敗", result.authorError);
    }

    @Test
    void provisionSite_何も作られずエラーも無い結果もそのまま返す() {
        world.respond("POST", PROVISION, new ProvisioningResult(null, null, null, null, null, null));

        ProvisioningService.Result result = service().provisionSite("WORDPRESS", Map.of(), null);

        assertEquals(null, result.defaultCategoryId);
        assertEquals(null, result.authorError);
    }

    @Test
    void provisionSite_ブリッジ呼び出し自体が失敗すればProvisioningException() {
        world.fail("POST", PROVISION, 500);

        assertThrows(ProvisioningException.class, () -> service().provisionSite("WORDPRESS", Map.of(), null));
    }
}
