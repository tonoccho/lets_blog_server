package com.letsblog.project.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.domain.SshKeyPair;
import com.letsblog.project.domain.StaticContent;
import com.letsblog.project.domain.StaticContentType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * issue #1237: openapi/project.json が format: date-time(RFC 3339、オフセット必須)と宣言する
 * 公開レスポンスの日時が、UTC の Z 終端で出力されること。
 *
 * <p>API の文字列形式は画面から観測できないため、Gherkin ではなくサービスレベルの
 * シリアライズテストで表現する。Spring MVC の既定コンバータと同じ Jackson 3 の
 * JsonMapper(WRITE_DATES_AS_TIMESTAMPS は既定で無効)を使う。
 *
 * <p>DB / エンティティは UTC の壁時計 LocalDateTime のまま、DTO への変換時に UTC を付けるだけ。
 */
class ResponseDateTimeSerializationTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 8, 20, 3, 35);
    private static final LocalDateTime UPDATED = LocalDateTime.of(2026, 9, 9, 1, 2, 3);
    private static final String CREATED_Z = "2026-09-08T20:03:35Z";
    private static final String UPDATED_Z = "2026-09-09T01:02:03Z";

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode json(Object value) {
        return mapper.valueToTree(value);
    }

    private Site site() {
        Site site = new Site();
        site.setId(1L);
        site.setName("n");
        site.setSiteKey("k");
        site.setCmsType(CmsType.WORDPRESS);
        site.setBaseUrl("https://example.com");
        site.setCreatedAt(CREATED);
        site.setUpdatedAt(UPDATED);
        return site;
    }

    @Test
    void siteResponse_はZ終端のRFC3339で返る() {
        JsonNode node = json(SiteResponse.from(site()));
        assertEquals(CREATED_Z, node.get("createdAt").asString());
        assertEquals(UPDATED_Z, node.get("updatedAt").asString());
    }

    @Test
    void siteDetailResponse_はZ終端のRFC3339で返る() {
        JsonNode node = json(SiteDetailResponse.from(site(), false, Map.of(), List.of()));
        assertEquals(CREATED_Z, node.get("createdAt").asString());
        assertEquals(UPDATED_Z, node.get("updatedAt").asString());
    }

    @Test
    void projectResponse_はZ終端のRFC3339で返る() {
        Project project = new Project();
        project.setId(1L);
        project.setName("p");
        project.setSlug("p");
        project.setCreatedAt(CREATED);
        project.setUpdatedAt(UPDATED);
        JsonNode node = json(ProjectResponse.from(project, null, null, null));
        assertEquals(CREATED_Z, node.get("createdAt").asString());
        assertEquals(UPDATED_Z, node.get("updatedAt").asString());
    }

    @Test
    void sshKeyPairSummaryResponse_はZ終端のRFC3339で返る() {
        SshKeyPair entity = new SshKeyPair("n", "c", "ssh-ed25519 AAA", new byte[0]);
        entity.setCreatedAt(CREATED);
        JsonNode node = json(SshKeyPairSummaryResponse.from(entity));
        assertEquals(CREATED_Z, node.get("createdAt").asString());
    }

    @Test
    void sshKeyPairGeneratedResponse_はZ終端のRFC3339で返る() {
        SshKeyPair entity = new SshKeyPair("n", "c", "ssh-ed25519 AAA", new byte[0]);
        entity.setCreatedAt(CREATED);
        JsonNode node = json(SshKeyPairGeneratedResponse.of(entity, "pem"));
        assertEquals(CREATED_Z, node.get("createdAt").asString());
    }

    @Test
    void staticContentResponse_はZ終端のRFC3339で返る() {
        StaticContent entity = new StaticContent();
        entity.setId(1L);
        entity.setSiteId(2L);
        entity.setContentType(StaticContentType.PRIVACY_POLICY);
        entity.setBody("b");
        entity.setCreatedAt(CREATED);
        entity.setUpdatedAt(UPDATED);
        JsonNode node = json(StaticContentResponse.from(entity));
        assertEquals(CREATED_Z, node.get("createdAt").asString());
        assertEquals(UPDATED_Z, node.get("updatedAt").asString());
    }

    @Test
    void 日時が未設定ならnullのまま返る() {
        Site site = site();
        site.setCreatedAt(null);
        site.setUpdatedAt(null);
        JsonNode node = json(SiteResponse.from(site));
        assertNull(node.get("createdAt").isNull() ? null : node.get("createdAt").asString());
        assertNull(node.get("updatedAt").isNull() ? null : node.get("updatedAt").asString());
    }
}
