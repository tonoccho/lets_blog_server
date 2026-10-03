package com.letsblog.content.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.letsblog.content.controller.InternalPostBridgeController;
import com.letsblog.content.domain.Post;
import com.letsblog.content.repository.PostRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * issue #1541: openapi/content.json が format: date-time(RFC 3339、オフセット必須)と宣言する
 * 内部ブリッジ応答({@code PostBridgeResponse})の日時が、UTC の Z 終端で出力されること。
 *
 * <p>ブリッジは画面から観測できない内部契約なので、Gherkin ではなくサービスレベルの
 * シリアライズテストで表現する。DB / エンティティは UTC の壁時計 LocalDateTime のまま。
 */
class PostBridgeResponseDateTimeSerializationTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode json(LocalDateTime scheduled, LocalDateTime published) {
        PostRepository repository = mock(PostRepository.class);
        Post post = new Post();
        post.setSiteId(1L);
        post.setWpPostId("42");
        post.setStatus("future");
        post.setPublishScheduledAt(scheduled);
        post.setLastPublishedAt(published);
        when(repository.findBySiteIdAndWpPostId(1L, "42")).thenReturn(Optional.of(post));
        return mapper.valueToTree(new InternalPostBridgeController(repository)
                .findBySiteAndWpPostId(1L, "42").getBody());
    }

    @Test
    void 予約日時と最終公開日時はZ終端のRFC3339で返る() {
        JsonNode node = json(LocalDateTime.of(2026, 12, 25, 9, 0, 0), LocalDateTime.of(2026, 9, 8, 20, 3, 35));
        assertEquals("2026-12-25T09:00:00Z", node.get("publishScheduledAt").asString());
        assertEquals("2026-09-08T20:03:35Z", node.get("lastPublishedAt").asString());
    }

    @Test
    void 日時が未設定ならnullのまま返る() {
        JsonNode node = json(null, null);
        assertTrue(node.get("publishScheduledAt").isNull());
        assertTrue(node.get("lastPublishedAt").isNull());
    }

    @Test
    void 予約日時だけ未設定でも最終公開日時は変換される() {
        JsonNode node = json(null, LocalDateTime.of(2026, 9, 8, 20, 3, 35));
        assertTrue(node.get("publishScheduledAt").isNull());
        assertEquals("2026-09-08T20:03:35Z", node.get("lastPublishedAt").asString());
    }

    @Test
    void 最終公開日時だけ未設定でも予約日時は変換される() {
        JsonNode node = json(LocalDateTime.of(2026, 12, 25, 9, 0, 0), null);
        assertEquals("2026-12-25T09:00:00Z", node.get("publishScheduledAt").asString());
        assertTrue(node.get("lastPublishedAt").isNull());
    }
}
