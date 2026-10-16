package com.letsblog.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.media.client.CmsMediaReferenceScan;
import com.letsblog.media.client.CmsMediaSummary;
import com.letsblog.media.client.CmsPostContentSummary;
import com.letsblog.media.client.MediaGcScanResult;
import com.letsblog.media.dto.MediaGarbageCollectionScanResponse;
import com.letsblog.media.testsupport.BrokenObjectMapper;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MediaGarbageCollectionServiceの未参照メディア判定(issue #573 stage3でlegacy-apiから移設)。
 *
 * <p>issue #1819: {@code CmsBridgeClient}・{@code GenerationJobClient}・{@code MediaGarbageCollectionJobRunner}は
 * 実物で、publishing-service・ai-serviceをフェイクHTTPサーバーが演じる。
 */
class MediaGarbageCollectionServiceTest {

    private final MediaWorld world = new MediaWorld();
    private final MediaGarbageCollectionService service = world.gcService;

    @AfterEach
    void close() {
        world.close();
    }

    private static CmsMediaSummary media(String id, String guid, String uploadedAt) {
        return new CmsMediaSummary(id, guid, "t" + id, "image/jpeg", uploadedAt);
    }

    private static CmsPostContentSummary post(String content, String thumbnailId) {
        return new CmsPostContentSummary("1", "post", "publish", content, thumbnailId);
    }

    // --- extractContentReferences ---

    @Test
    void extractContentReferences_wpImageクラスを検出する() {
        Set<String> ids = service.extractContentReferences("<img class=\"wp-image-42\" src=\"x.jpg\">");
        assertEquals(Set.of("42"), ids);
    }

    @Test
    void extractContentReferences_Gutenbergブロックのid属性を検出する() {
        Set<String> ids = service.extractContentReferences(
                "<!-- wp:image {\"id\":77,\"sizeSlug\":\"large\"} --><figure></figure><!-- /wp:image -->");
        assertEquals(Set.of("77"), ids);
    }

    @Test
    void extractContentReferences_属性の無いブロックとid属性の無いブロックは参照にしない() {
        assertTrue(service.extractContentReferences("<!-- wp:image --><figure></figure><!-- /wp:image -->").isEmpty());
        assertTrue(service.extractContentReferences("<!-- wp:cover {\"sizeSlug\":\"large\"} /-->").isEmpty());
    }

    @Test
    void extractContentReferences_ギャラリーショートコードのidsを検出する() {
        Set<String> ids = service.extractContentReferences("[gallery ids=\"1,2,3\"]");
        assertEquals(Set.of("1", "2", "3"), ids);
    }

    @Test
    void extractContentReferences_null空文字は空集合を返す() {
        assertTrue(service.extractContentReferences(null).isEmpty());
        assertTrue(service.extractContentReferences("").isEmpty());
        assertTrue(service.extractContentReferences("本文中に42という数字があるだけ").isEmpty());
    }

    // --- extractReferencedIds ---

    @Test
    void extractReferencedIds_サムネイル_本文_設定の参照を合算する() {
        Map<String, String> settings = new HashMap<>();
        settings.put("site_icon", "30");
        settings.put("custom_logo", "");
        settings.put("header_image", "0");
        settings.put("background_image", null);
        settings.put("blank", "  ");
        CmsMediaReferenceScan scan = new CmsMediaReferenceScan(
                List.of(post("<img class=\"wp-image-10\">", "20")), settings);

        Set<String> ids = service.extractReferencedIds(scan);

        assertEquals(Set.of("10", "20", "30"), ids);
    }

    // --- scan ---

    @Test
    void scan_未参照メディアのみ返す() {
        CmsMediaSummary referenced = media("10", "https://local.test/img1.jpg", "2024-01-01");
        CmsMediaSummary unreferenced = media("99", "https://local.test/img2.jpg", "2024-01-02");
        world.publishingScan(new MediaGcScanResult(List.of(referenced, unreferenced),
                new CmsMediaReferenceScan(List.of(post("<img class=\"wp-image-10\">", "")), Map.of())));

        MediaGarbageCollectionScanResponse response = service.scan(1L, "local", "Bearer token");

        assertEquals(1, response.items().size());
        assertEquals("99", response.items().get(0).mediaId());
        assertEquals(2, response.totalMediaCount());
        assertEquals(1, response.referencedMediaCount());
        assertEquals(1, response.unreferencedMediaCount());
        var request = world.http.requests("GET", "/api/internal/publishing/projects/1/media-scan").get(0);
        assertEquals("environment=local", request.query());
        assertEquals("Bearer token", request.header("Authorization"), "呼び出し元のBearerトークンを転送する");
    }

    @Test
    void scan_未参照メディアは新しい順に並ぶ() {
        world.publishingScan(new MediaGcScanResult(
                List.of(media("1", "u1", "2024-01-01"), media("3", "u3", "2024-03-01"), media("2", "u2", "2024-02-01")),
                new CmsMediaReferenceScan(List.of(), Map.of())));

        MediaGarbageCollectionScanResponse response = service.scan(1L, "local", "Bearer token");

        assertEquals(List.of("3", "2", "1"), response.items().stream().map(i -> i.mediaId()).toList());
    }

    @Test
    void scan_URLの部分一致による二次チェックで誤検出を防ぐ() {
        CmsMediaSummary pastedRaw = media("55", "https://local.test/wp-content/uploads/raw.jpg", "2024-01-01");
        world.publishingScan(new MediaGcScanResult(List.of(pastedRaw), new CmsMediaReferenceScan(
                List.of(post("<img src=\"https://local.test/wp-content/uploads/raw.jpg\">", "")), Map.of())));

        MediaGarbageCollectionScanResponse response = service.scan(1L, "local", "Bearer token");

        assertTrue(response.items().isEmpty());
        assertEquals(1, response.referencedMediaCount());
    }

    @Test
    void scan_guidが無い_本文が無い_本文に含まれないメディアは未参照のまま() {
        world.publishingScan(new MediaGcScanResult(
                List.of(media("1", null, "2024-01-01"), media("2", "  ", "2024-01-02"),
                        media("3", "https://local.test/c.jpg", "2024-01-03")),
                new CmsMediaReferenceScan(List.of(post(null, null), post("本文にURLは無い", null)), Map.of())));

        MediaGarbageCollectionScanResponse response = service.scan(1L, "local", "Bearer token");

        assertEquals(3, response.unreferencedMediaCount());
        assertEquals(0, response.referencedMediaCount());
    }

    // --- startDelete ---

    @Test
    void startDelete_ジョブをai_service側で作成しジョブランナーを起動する() {
        GenerationJobSummary response = service.startDelete(
                1L, "local", List.of("10", "20"), 9L, "keycloak-sub-1", "Bearer token");

        assertEquals(9L, response.id());
        assertEquals("running", response.status());
        MediaWorld.JobCreate created = world.jobCreates().get(0);
        assertEquals("media_garbage_collection_delete", created.type());
        assertEquals(List.of("10", "20"), List.of(created.requestPayload().get("mediaIds").get(0).asText(),
                created.requestPayload().get("mediaIds").get(1).asText()));
        assertEquals("Bearer token", world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0)
                .header("Authorization"));
        // ランナーが起動し、各メディアの削除を依頼してジョブを完了にする。
        assertEquals(2, world.http.requests("DELETE", "/api/internal/publishing/projects/1/media/.*").size());
        JsonNode done = world.jobUpdates("done").get(0).payload();
        assertEquals(2, done.get("deletedCount").asInt());
    }

    @Test
    void startDelete_リクエストのJSON化に失敗しても空のJSONでジョブを作る() {
        MediaGarbageCollectionService broken = new MediaGarbageCollectionService(
                world.cmsBridgeClient, world.generationJobClient, world.gcRunner, BrokenObjectMapper.create());

        broken.startDelete(1L, "local", List.of("10"), 9L, "sub", "Bearer token");

        var sent = world.bodyOf(world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0));
        assertEquals("{}", sent.get("requestPayload").asText(), "requestPayloadは空のJSONオブジェクト");
    }
}
