package com.letsblog.content.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.content.client.ProjectBridgeClient;
import com.letsblog.content.contentcache.AmazonProductParser;
import com.letsblog.content.contentcache.ContentCacheService;
import com.letsblog.content.contentcache.OgpMetadataParser;
import com.letsblog.content.contentcache.OutboundUrlGuard;
import com.letsblog.content.contentcache.PlaywrightPageFetcher;
import com.letsblog.content.controller.PostController;
import com.letsblog.content.domain.ContentCache;
import com.letsblog.content.domain.ContentType;
import com.letsblog.content.domain.CustomTag;
import com.letsblog.content.domain.CustomTagTemplate;
import com.letsblog.content.domain.Post;
import com.letsblog.content.repository.ContentCacheRepository;
import com.letsblog.content.repository.PostRepository;
import com.letsblog.content.service.AdminAuthorizationService;
import com.letsblog.content.service.CurrentActorService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private final ContentCacheRepository cacheRepository = mock(ContentCacheRepository.class);
    private final PlaywrightPageFetcher fetcher = mock(PlaywrightPageFetcher.class);
    private final OgpMetadataParser ogp = mock(OgpMetadataParser.class);

    private ContentCacheService cacheService() {
        return new ContentCacheService(
                cacheRepository, fetcher, ogp, mock(AmazonProductParser.class),
                new ObjectMapper(), mock(OutboundUrlGuard.class), 1_000_000L);
    }

    @Test
    void contentCacheResponse_キャッシュヒットはDBの壁時計と同じ実時刻のZ終端で返る() {
        ContentCache cache = new ContentCache();
        cache.setUrl("https://example.com/a");
        cache.setContentType(ContentType.BLOGCARD);
        cache.setDataJson("{\"title\":\"t\"}");
        cache.setLastCheckedAt(A);
        cache.setLastUpdatedAt(B);
        when(cacheRepository.findByUrlHash(anyString())).thenReturn(Optional.of(cache));

        JsonNode node = json(cacheService().resolve("https://example.com/a"));

        assertEquals(A_Z, node.get("lastCheckedAt").asString());
        assertEquals(B_Z, node.get("lastUpdatedAt").asString());
    }

    @Test
    void contentCacheResponse_新規取得もZ終端で返る() {
        when(cacheRepository.findByUrlHash(anyString())).thenReturn(Optional.empty());
        when(fetcher.fetchHtml(anyString())).thenReturn("<html></html>");
        when(ogp.parse(anyString(), anyString())).thenReturn(Map.of("title", "t"));
        when(cacheRepository.save(any(ContentCache.class))).thenAnswer(i -> i.getArgument(0));

        JsonNode node = json(cacheService().resolve("https://example.com/new"));

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

    @Test
    void postSummaryResponse_はZ終端のRFC3339で返る() {
        PostRepository postRepository = mock(PostRepository.class);
        ProjectBridgeClient bridge = mock(ProjectBridgeClient.class);
        CurrentActorService actor = mock(CurrentActorService.class);
        AdminAuthorizationService authorization = mock(AdminAuthorizationService.class);
        Post post = new Post();
        post.setId(1L);
        post.setSiteId(1L);
        post.setStatus("publish");
        post.setUpdatedAt(B);
        post.setLastPublishedAt(A);
        post.setPublishScheduledAt(B);
        when(postRepository.findAll()).thenReturn(new ArrayList<>(List.of(post)));
        when(bridge.listSites(any())).thenReturn(List.of());
        when(authorization.accessibleSiteIds()).thenReturn(Optional.empty());
        PostController controller = new PostController(postRepository, bridge, actor, new ObjectMapper(), authorization);

        JsonNode node = json(controller.list(null, null).get(0));

        assertEquals(A_Z, node.get("lastPublishedAt").asString());
        assertEquals(B_Z, node.get("publishScheduledAt").asString());
    }

    @Test
    void 日時が未設定ならnullのまま返る() {
        PostRepository postRepository = mock(PostRepository.class);
        ProjectBridgeClient bridge = mock(ProjectBridgeClient.class);
        AdminAuthorizationService authorization = mock(AdminAuthorizationService.class);
        Post post = new Post();
        post.setId(1L);
        post.setSiteId(1L);
        post.setStatus("draft");
        post.setUpdatedAt(B);
        when(postRepository.findAll()).thenReturn(new ArrayList<>(List.of(post)));
        when(bridge.listSites(any())).thenReturn(List.of());
        when(authorization.accessibleSiteIds()).thenReturn(Optional.empty());
        PostController controller = new PostController(
                postRepository, bridge, mock(CurrentActorService.class), new ObjectMapper(), authorization);

        JsonNode node = json(controller.list(null, null).get(0));

        assertTrue(node.get("lastPublishedAt").isNull());
        assertTrue(node.get("publishScheduledAt").isNull());
    }
}
