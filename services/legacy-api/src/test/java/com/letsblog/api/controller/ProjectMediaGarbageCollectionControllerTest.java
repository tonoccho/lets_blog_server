package com.letsblog.api.controller;

import com.letsblog.api.dto.GenerationJobResponse;
import com.letsblog.api.dto.MediaGarbageCollectionDeleteRequest;
import com.letsblog.api.dto.MediaGarbageCollectionScanResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.ForbiddenException;
import com.letsblog.api.service.MediaGarbageCollectionService;
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

@ExtendWith(MockitoExtension.class)
class ProjectMediaGarbageCollectionControllerTest {

    @Mock
    private MediaGarbageCollectionService mediaGarbageCollectionService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private CurrentActorService currentActorService;

    private ProjectMediaGarbageCollectionController controller() {
        return new ProjectMediaGarbageCollectionController(
                mediaGarbageCollectionService, adminAuthorizationService, currentActorService);
    }

    @Test
    void scan_admin権限で委譲する() {
        MediaGarbageCollectionScanResponse response =
                new MediaGarbageCollectionScanResponse("local", List.of(), 3, 2, 1);
        when(mediaGarbageCollectionService.scan(1L, "local")).thenReturn(response);

        MediaGarbageCollectionScanResponse result = controller().scan(1L, "local");

        assertEquals(response, result);
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void scan_admin権限がなければ例外() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().scan(1L, "local"));
    }

    @Test
    void delete_actorIdを取得してサービスへ委譲する() {
        when(currentActorService.getCurrentActorId()).thenReturn(9L);
        GenerationJobResponse response =
                new GenerationJobResponse(123L, "media_garbage_collection_delete", "running",
                        LocalDateTime.now(), LocalDateTime.now());
        when(mediaGarbageCollectionService.startDelete(1L, "local", List.of("10", "20"), 9L))
                .thenReturn(response);

        GenerationJobResponse result = controller()
                .delete(1L, "local", new MediaGarbageCollectionDeleteRequest(List.of("10", "20")));

        assertEquals(response, result);
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void delete_admin権限がなければ例外() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller().delete(1L, "local", new MediaGarbageCollectionDeleteRequest(List.of("10"))));
    }
}
