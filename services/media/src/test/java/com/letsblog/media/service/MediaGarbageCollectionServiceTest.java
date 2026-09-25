package com.letsblog.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.client.CmsBridgeClient;
import com.letsblog.media.client.CmsMediaReferenceScan;
import com.letsblog.media.client.CmsMediaSummary;
import com.letsblog.media.client.CmsPostContentSummary;
import com.letsblog.media.client.GenerationJobClient;
import com.letsblog.media.client.GenerationJobSummary;
import com.letsblog.media.client.MediaGcScanResult;
import com.letsblog.media.dto.MediaGarbageCollectionScanResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MediaGarbageCollectionServiceの回帰テスト。#573 stage3でlegacy-apiから移設したのに伴い、
 * Project/Site/CmsAdapterへの直接アクセスがCmsBridgeClient経由のHTTP呼び出しに置き換わったことを
 * 検証する。未参照判定の正規表現ロジック自体(extractContentReferences/extractReferencedIds)は
 * legacy-api時代のテストをそのまま移設した(ロジック自体は変更していない)。
 */
@ExtendWith(MockitoExtension.class)
class MediaGarbageCollectionServiceTest {

    @Mock
    private CmsBridgeClient cmsBridgeClient;
    @Mock
    private GenerationJobClient generationJobClient;
    @Mock
    private MediaGarbageCollectionJobRunner mediaGarbageCollectionJobRunner;

    private MediaGarbageCollectionService service() {
        return new MediaGarbageCollectionService(
                cmsBridgeClient, generationJobClient, mediaGarbageCollectionJobRunner, new ObjectMapper());
    }

    // --- extractContentReferences ---

    @Test
    void extractContentReferences_wpImageクラスを検出する() {
        Set<String> ids = service().extractContentReferences("<img class=\"wp-image-42\" src=\"x.jpg\">");
        assertEquals(Set.of("42"), ids);
    }

    @Test
    void extractContentReferences_Gutenbergブロックのid属性を検出する() {
        Set<String> ids = service().extractContentReferences(
                "<!-- wp:image {\"id\":77,\"sizeSlug\":\"large\"} --><figure></figure><!-- /wp:image -->");
        assertEquals(Set.of("77"), ids);
    }

    @Test
    void extractContentReferences_ギャラリーショートコードのidsを検出する() {
        Set<String> ids = service().extractContentReferences("[gallery ids=\"1,2,3\"]");
        assertEquals(Set.of("1", "2", "3"), ids);
    }

    @Test
    void extractContentReferences_null空文字は空集合を返す() {
        assertTrue(service().extractContentReferences(null).isEmpty());
        assertTrue(service().extractContentReferences("").isEmpty());
        assertTrue(service().extractContentReferences("本文中に42という数字があるだけ").isEmpty());
    }

    // --- extractReferencedIds ---

    @Test
    void extractReferencedIds_サムネイル_本文_設定の参照を合算する() {
        CmsMediaReferenceScan scan = new CmsMediaReferenceScan(
                List.of(new CmsPostContentSummary("1", "post", "publish",
                        "<img class=\"wp-image-10\">", "20")),
                Map.of("site_icon", "30", "custom_logo", "", "header_image", "0", "background_image", ""));

        Set<String> ids = service().extractReferencedIds(scan);

        assertEquals(Set.of("10", "20", "30"), ids);
    }

    // --- scan ---

    @Test
    void scan_未参照メディアのみ返す() {
        MediaGarbageCollectionService service = service();
        CmsMediaSummary referenced = new CmsMediaSummary("10", "https://local.test/img1.jpg", "img1", "image/jpeg", "2024-01-01");
        CmsMediaSummary unreferenced = new CmsMediaSummary("99", "https://local.test/img2.jpg", "img2", "image/jpeg", "2024-01-02");
        CmsMediaReferenceScan refs = new CmsMediaReferenceScan(
                List.of(new CmsPostContentSummary("1", "post", "publish", "<img class=\"wp-image-10\">", "")),
                Map.of());
        when(cmsBridgeClient.scanMedia(eq(1L), eq("local"), any()))
                .thenReturn(new MediaGcScanResult(List.of(referenced, unreferenced), refs));

        MediaGarbageCollectionScanResponse response = service.scan(1L, "local", "Bearer token");

        assertEquals(1, response.items().size());
        assertEquals("99", response.items().get(0).mediaId());
        assertEquals(2, response.totalMediaCount());
        assertEquals(1, response.referencedMediaCount());
        assertEquals(1, response.unreferencedMediaCount());
        verify(cmsBridgeClient).scanMedia(1L, "local", "Bearer token");
    }

    @Test
    void scan_URLの部分一致による二次チェックで誤検出を防ぐ() {
        MediaGarbageCollectionService service = service();
        CmsMediaSummary pastedRaw = new CmsMediaSummary(
                "55", "https://local.test/wp-content/uploads/raw.jpg", "raw", "image/jpeg", "2024-01-01");
        CmsMediaReferenceScan refs = new CmsMediaReferenceScan(
                List.of(new CmsPostContentSummary("1", "post", "publish",
                        "<img src=\"https://local.test/wp-content/uploads/raw.jpg\">", "")),
                Map.of());
        when(cmsBridgeClient.scanMedia(eq(1L), eq("local"), any()))
                .thenReturn(new MediaGcScanResult(List.of(pastedRaw), refs));

        MediaGarbageCollectionScanResponse response = service.scan(1L, "local", "Bearer token");

        assertTrue(response.items().isEmpty());
        assertEquals(1, response.referencedMediaCount());
    }

    // --- startDelete ---

    @Test
    void startDelete_ジョブをlegacyApi側で作成しジョブランナーを起動する() {
        MediaGarbageCollectionService service = service();
        GenerationJobSummary created = new GenerationJobSummary(
                123L, "media_garbage_collection_delete", "running", LocalDateTime.now(), LocalDateTime.now());
        when(generationJobClient.create(eq("media_garbage_collection_delete"), anyString(), eq("Bearer token")))
                .thenReturn(created);

        GenerationJobSummary response = service.startDelete(
                1L, "local", List.of("10", "20"), 9L, "keycloak-sub-1", "Bearer token");

        assertEquals(123L, response.id());
        assertEquals("running", response.status());
        assertEquals("media_garbage_collection_delete", response.type());

        verify(mediaGarbageCollectionJobRunner).runDelete(
                123L, 1L, "local", List.of("10", "20"), 9L, "keycloak-sub-1", "Bearer token");
    }
}
