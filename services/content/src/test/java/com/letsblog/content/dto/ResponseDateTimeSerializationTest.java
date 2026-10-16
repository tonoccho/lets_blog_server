package com.letsblog.content.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.letsblog.content.controller.PostController;
import com.letsblog.content.domain.ContentCache;
import com.letsblog.content.domain.ContentType;
import com.letsblog.content.domain.CustomTag;
import com.letsblog.content.domain.CustomTagTemplate;
import com.letsblog.content.domain.Post;
import com.letsblog.content.repository.PostRepository;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * issue #1536: openapi/content.json が format: date-time(RFC 3339、オフセット必須)と宣言する
 * 公開レスポンスの日時が、UTC の Z 終端で出力されること。
 *
 * <p>API の文字列形式は画面から観測できないため、Gherkin ではなくサービスレベルの
 * シリアライズテストで表現する。DTO は既存の組み立て経路(コントローラ / サービス / from)を通して作り、
 * Spring MVC の既定コンバータと同じ Jackson 3 の JsonMapper で JSON にする。
 *
 * <p>DB / エンティティは UTC の壁時計 LocalDateTime のまま、DTO への変換時に UTC を付けるだけ。
 */
class ResponseDateTimeSerializationTest {

    private static final LocalDateTime A = LocalDateTime.of(2026, 9, 8, 20, 3, 35);
    private static final LocalDateTime B = LocalDateTime.of(2026, 9, 9, 1, 2, 3);
    private static final String A_Z = "2026-09-08T20:03:35Z";
    private static final String B_Z = "2026-09-09T01:02:03Z";

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode json(Object value) {
        return mapper.valueToTree(value);
    }

    private ContentWorld world;

    @BeforeEach
    void setUp() {
        // キャッシュの期限切れで取り直さないよう、TTL を十分に長くする。
        world = new ContentWorld(1_000_000L);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void contentCacheResponse_キャッシュヒットはDBの壁時計と同じ実時刻のZ終端で返る() {
        ContentCache cache = world.cached("https://example.com/a", ContentType.BLOGCARD, Map.of("title", "t"), A);
        cache.setLastUpdatedAt(B);

        JsonNode node = json(world.contentCacheService.resolve("https://example.com/a"));

        assertEquals(A_Z, node.get("lastCheckedAt").asString());
        assertEquals(B_Z, node.get("lastUpdatedAt").asString());
    }

    @Test
    void contentCacheResponse_新規取得もZ終端で返る() {
        world.page("https://example.com/new", "<html></html>");

        JsonNode node = json(world.contentCacheService.resolve("https://example.com/new"));

        assertTrue(node.get("lastCheckedAt").asString().matches("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z"),
                node.get("lastCheckedAt").asString());
        assertTrue(node.get("lastUpdatedAt").asString().matches("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z"),
                node.get("lastUpdatedAt").asString());
    }

    @Test
    void customTagResponse_はZ終端のRFC3339で返る() {
        CustomTag tag = new CustomTag();
        tag.setId(1L);
        tag.setTagName("t");
        tag.setHtmlTemplate("<p/>");
        tag.setCreatedAt(A);
        tag.setUpdatedAt(B);

        JsonNode node = json(CustomTagResponse.from(tag));

        assertEquals(A_Z, node.get("createdAt").asString());
        assertEquals(B_Z, node.get("updatedAt").asString());
    }

    @Test
    void customTagTemplateResponse_はZ終端のRFC3339で返る() {
        CustomTagTemplate template = new CustomTagTemplate();
        template.setId(1L);
        template.setTemplateName("t");
        template.setHtmlTemplate("<p/>");
        template.setCreatedAt(A);
        template.setUpdatedAt(B);

        JsonNode node = json(CustomTagTemplateResponse.from(template));

        assertEquals(A_Z, node.get("createdAt").asString());
        assertEquals(B_Z, node.get("updatedAt").asString());
    }

    private PostController postController(Post post) {
        PostRepository postRepository = mock(PostRepository.class);
        when(postRepository.findAll()).thenReturn(new ArrayList<>(List.of(post)));
        world.http.json("GET", "/api/internal/project/sites", "[]");
        world.actingAsAdmin(1L);
        return new PostController(postRepository, world.projectBridgeClient, world.currentActorService,
                world.objectMapper, world.adminAuthorizationService);
    }

    @Test
    void postSummaryResponse_はZ終端のRFC3339で返る() {
        Post post = new Post();
        post.setId(1L);
        post.setSiteId(1L);
        post.setStatus("publish");
        post.setUpdatedAt(B);
        post.setLastPublishedAt(A);
        post.setPublishScheduledAt(B);

        JsonNode node = json(postController(post).list(null, null).get(0));

        assertEquals(A_Z, node.get("lastPublishedAt").asString());
        assertEquals(B_Z, node.get("publishScheduledAt").asString());
    }

    @Test
    void 日時が未設定ならnullのまま返る() {
        Post post = new Post();
        post.setId(1L);
        post.setSiteId(1L);
        post.setStatus("draft");
        post.setUpdatedAt(B);

        JsonNode node = json(postController(post).list(null, null).get(0));

        assertTrue(node.get("lastPublishedAt").isNull());
        assertTrue(node.get("publishScheduledAt").isNull());
    }
}
