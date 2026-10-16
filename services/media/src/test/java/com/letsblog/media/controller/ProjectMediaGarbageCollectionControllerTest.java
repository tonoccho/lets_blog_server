package com.letsblog.media.controller;

import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.media.client.CmsMediaReferenceScan;
import com.letsblog.media.client.CmsMediaSummary;
import com.letsblog.media.client.MediaGcScanResult;
import com.letsblog.media.dto.GenerationJobResponse;
import com.letsblog.media.dto.MediaGarbageCollectionDeleteRequest;
import com.letsblog.media.dto.MediaGarbageCollectionScanResponse;
import com.letsblog.media.service.ForbiddenException;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * legacy-apiから移設(issue #573 stage3)。requireAdmin()チェック、CurrentActorServiceから
 * 取得したactorId/actorKeycloakSub/Bearerトークンが正しくMediaGarbageCollectionServiceへ
 * 渡されることを検証する。
 *
 * <p>issue #1820: GCサービス・認可・現在の操作者は実物で、publishing-service・ai-service・identity-serviceを
 * フェイクHTTPサーバーが演じる。
 */
class ProjectMediaGarbageCollectionControllerTest {

    private static final String SCAN_PATH = "/api/internal/publishing/projects/1/media-scan";

    private final MediaWorld world = new MediaWorld();

    private ProjectMediaGarbageCollectionController controller;

    @BeforeEach
    void setUp() {
        world.actingAsAdmin(1L).signedInAs("keycloak-sub-1");
        controller = new ProjectMediaGarbageCollectionController(
                world.gcService, world.adminAuthorizationService, world.currentActorService);
    }

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    void scan_requireAdminを呼びBearerトークンを転送する() {
        world.publishingScan(new MediaGcScanResult(
                List.of(new CmsMediaSummary("10", "https://x/10.jpg", "t", "image/jpeg", "2026-01-01T00:00:00Z")),
                new CmsMediaReferenceScan(List.of(), Map.of())));

        MediaGarbageCollectionScanResponse response = controller.scan(1L, "local");

        assertEquals("local", response.environment());
        assertEquals(1, response.totalMediaCount());
        assertEquals(1, response.unreferencedMediaCount());
        assertEquals("Bearer token-1", world.http.requests("GET", SCAN_PATH).get(0).header("Authorization"));
    }

    @Test
    void scan_admin以外は403() {
        world.actingAsUser(10L);

        assertThrows(ForbiddenException.class, () -> controller.scan(1L, "local"));

        assertTrue(world.http.requests("GET", SCAN_PATH).isEmpty());
    }

    @Test
    void delete_actorIdとBearerトークンを転送しジョブを返す() {
        GenerationJobResponse response = controller.delete(
                1L, "local", new MediaGarbageCollectionDeleteRequest(List.of("10")));

        assertEquals(9L, response.id());
        assertEquals("Bearer token-1", world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0)
                .header("Authorization"));
        assertEquals(1, world.http.requests("DELETE", "/api/internal/publishing/projects/1/media/10").size());
        // 操作者(JWTのsubとidentity-serviceのid)は監査ログのメッセージへ渡る。
        AuditLogMessage audit = (AuditLogMessage) world.publishedMessages().get(0);
        assertEquals(1L, audit.userId());
        assertEquals("keycloak-sub-1", audit.actorKeycloakSub());
    }

    @Test
    void delete_admin以外は403でジョブを作らない() {
        world.actingAsUser(10L);

        assertThrows(ForbiddenException.class, () -> controller.delete(
                1L, "local", new MediaGarbageCollectionDeleteRequest(List.of("10"))));

        assertTrue(world.http.requests("POST", "/api/internal/ai/generation-jobs").isEmpty());
    }
}
