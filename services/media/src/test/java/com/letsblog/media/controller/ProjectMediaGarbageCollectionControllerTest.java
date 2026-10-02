package com.letsblog.media.controller;

import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.media.dto.MediaGarbageCollectionDeleteRequest;
import com.letsblog.media.dto.MediaGarbageCollectionScanResponse;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.CurrentActorService;
import com.letsblog.media.service.ForbiddenException;
import com.letsblog.media.service.MediaGarbageCollectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * legacy-apiから移設(issue #573 stage3)。requireAdmin()チェック、CurrentActorServiceから
 * 取得したactorId/actorKeycloakSub/Bearerトークンが正しくMediaGarbageCollectionServiceへ
 * 渡されることを検証する。
 */
@ExtendWith(MockitoExtension.class)
class ProjectMediaGarbageCollectionControllerTest {

    @Mock
    private MediaGarbageCollectionService mediaGarbageCollectionService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private CurrentActorService currentActorService;

    private ProjectMediaGarbageCollectionController controller;

    @BeforeEach
    void setUp() {
        controller = new ProjectMediaGarbageCollectionController(
                mediaGarbageCollectionService, adminAuthorizationService, currentActorService);
    }

    @Test
    void scan_requireAdminを呼びBearerトークンを転送する() {
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer token-1");
        MediaGarbageCollectionScanResponse expected =
                new MediaGarbageCollectionScanResponse("local", List.of(), 0, 0, 0);
        when(mediaGarbageCollectionService.scan(1L, "local", "Bearer token-1")).thenReturn(expected);

        MediaGarbageCollectionScanResponse response = controller.scan(1L, "local");

        assertEquals(expected, response);
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void scan_admin以外は403() {
        doThrow(new ForbiddenException("admin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.scan(1L, "local"));
    }

    @Test
    void delete_actorIdとactorKeycloakSubとBearerトークンを転送する() {
        when(currentActorService.getCurrentActorId()).thenReturn(9L);
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("keycloak-sub-1");
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer token-2");
        GenerationJobSummary job = new GenerationJobSummary(
                5L, "media_garbage_collection_delete", "running", LocalDateTime.now(), LocalDateTime.now());
        when(mediaGarbageCollectionService.startDelete(1L, "local", List.of("10"), 9L, "keycloak-sub-1", "Bearer token-2"))
                .thenReturn(job);

        GenerationJobSummary response = controller.delete(1L, "local", new MediaGarbageCollectionDeleteRequest(List.of("10")));

        assertEquals(5L, response.id());
        verify(adminAuthorizationService).requireAdmin();
    }
}
