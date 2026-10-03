package com.letsblog.project.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * issue #1541: openapi/project.json が format: date-time(RFC 3339、オフセット必須)と宣言する
 * 内部ブリッジ応答({@code SiteBridgeResponse} / {@code ProjectBridgeResponse})の日時が、
 * UTC の Z 終端で出力されること。
 *
 * <p>ブリッジは画面から観測できない内部契約なので、Gherkin ではなくサービスレベルの
 * シリアライズテストで表現する。DB / エンティティは UTC の壁時計 LocalDateTime のまま。
 */
class BridgeResponseDateTimeSerializationTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 8, 20, 3, 35);
    private static final LocalDateTime UPDATED = LocalDateTime.of(2026, 9, 9, 1, 2, 3);

    private final JsonMapper mapper = JsonMapper.builder().build();

    private Site site(LocalDateTime created, LocalDateTime updated) {
        Site site = new Site();
        site.setId(1L);
        site.setName("n");
        site.setSiteKey("k");
        site.setCmsType(CmsType.WORDPRESS);
        site.setBaseUrl("https://example.com");
        site.setCreatedAt(created);
        site.setUpdatedAt(updated);
        return site;
    }

    private Project project(LocalDateTime created, LocalDateTime updated) {
        Project project = new Project();
        project.setId(1L);
        project.setName("p");
        project.setSlug("p");
        project.setCreatedAt(created);
        project.setUpdatedAt(updated);
        return project;
    }

    @Test
    void siteBridgeResponse_はZ終端のRFC3339で返る() {
        JsonNode node = mapper.valueToTree(SiteBridgeResponse.from(site(CREATED, UPDATED)));
        assertEquals("2026-09-08T20:03:35Z", node.get("createdAt").asString());
        assertEquals("2026-09-09T01:02:03Z", node.get("updatedAt").asString());
    }

    @Test
    void projectBridgeResponse_はZ終端のRFC3339で返る() {
        JsonNode node = mapper.valueToTree(ProjectBridgeResponse.from(project(CREATED, UPDATED)));
        assertEquals("2026-09-08T20:03:35Z", node.get("createdAt").asString());
        assertEquals("2026-09-09T01:02:03Z", node.get("updatedAt").asString());
    }

    @Test
    void siteBridgeResponse_日時が未設定ならnullのまま返る() {
        JsonNode node = mapper.valueToTree(SiteBridgeResponse.from(site(null, null)));
        assertTrue(node.get("createdAt").isNull());
        assertTrue(node.get("updatedAt").isNull());
    }

    @Test
    void projectBridgeResponse_日時が未設定ならnullのまま返る() {
        JsonNode node = mapper.valueToTree(ProjectBridgeResponse.from(project(null, null)));
        assertTrue(node.get("createdAt").isNull());
        assertTrue(node.get("updatedAt").isNull());
    }

    @Test
    void siteBridgeResponse_createdだけ未設定でもupdatedは変換される() {
        JsonNode node = mapper.valueToTree(SiteBridgeResponse.from(site(null, UPDATED)));
        assertTrue(node.get("createdAt").isNull());
        assertEquals("2026-09-09T01:02:03Z", node.get("updatedAt").asString());
    }

    @Test
    void projectBridgeResponse_updatedだけ未設定でもcreatedは変換される() {
        JsonNode node = mapper.valueToTree(ProjectBridgeResponse.from(project(CREATED, null)));
        assertEquals("2026-09-08T20:03:35Z", node.get("createdAt").asString());
        assertTrue(node.get("updatedAt").isNull());
    }
}
