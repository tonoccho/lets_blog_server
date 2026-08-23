package com.letsblog.logwriter.controller;

import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.logwriter.dto.OperationLogRequest;
import com.letsblog.logwriter.dto.UnifiedLogEntryResponse;
import com.letsblog.logwriter.service.CurrentActorService;
import com.letsblog.logwriter.service.ForbiddenException;
import com.letsblog.logwriter.service.OperationLogService;
import com.letsblog.logwriter.service.UnifiedOperationLogService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationLogControllerTest {

    @Mock
    private OperationLogService service;

    @Mock
    private UnifiedOperationLogService unifiedOperationLogService;

    @Mock
    private CurrentActorService currentActorService;

    private OperationLogController controller() {
        return new OperationLogController(service, unifiedOperationLogService, currentActorService);
    }

    @Test
    void record_actorが解決できればuserIdで保存する() {
        OperationLogController controller = controller();
        when(currentActorService.tryGetCurrentActorId()).thenReturn(1L);
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("keycloak-sub-1");
        OperationLogRequest request = new OperationLogRequest("op-1", "GET", "/api/sites", 200, 42L, true, null);

        ResponseEntity<Void> response = controller.record(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(service, times(1)).record(captor.capture());
        assertEquals(1L, captor.getValue().getUserId());
        assertEquals("keycloak-sub-1", captor.getValue().getActorKeycloakSub());
        assertEquals("op-1", captor.getValue().getOperationId());
    }

    @Test
    void record_未ログインならUnauthorizedを返す() {
        OperationLogController controller = controller();
        when(currentActorService.tryGetCurrentActorId()).thenReturn(null);
        OperationLogRequest request = new OperationLogRequest("op-1", "GET", "/api/sites", 200, 42L, true, null);

        ResponseEntity<Void> response = controller.record(request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void list_本人のログのみ取得する() {
        OperationLogController controller = controller();
        when(currentActorService.getCurrentActorId()).thenReturn(1L);
        Pageable pageable = PageRequest.of(0, 20);
        Page<OperationLog> page = new PageImpl<>(List.of(new OperationLog()));
        when(service.findByUser(1L, pageable)).thenReturn(page);

        Page<OperationLog> result = controller.list(pageable);

        assertEquals(1, result.getTotalElements());
        verify(service).findByUser(1L, pageable);
    }

    @Test
    void list_未ログインならForbidden() {
        OperationLogController controller = controller();
        when(currentActorService.getCurrentActorId()).thenReturn(null);
        Pageable pageable = PageRequest.of(0, 20);

        assertThrows(ForbiddenException.class, () -> controller.list(pageable));
    }

    @Test
    void trace_operationIdに紐づくログを昇順で取得する() {
        OperationLogController controller = controller();
        when(currentActorService.getCurrentActorId()).thenReturn(1L);
        when(service.findTrace(1L, "op-1")).thenReturn(List.of(new OperationLog()));

        List<OperationLog> result = controller.trace("op-1");

        assertEquals(1, result.size());
        verify(service).findTrace(1L, "op-1");
    }

    @Test
    void listUnified_ログイン中ユーザーのadmin区分を渡して統合サービスへ委譲する() {
        OperationLogController controller = controller();
        when(currentActorService.getCurrentActorId()).thenReturn(1L);
        when(currentActorService.isAdmin()).thenReturn(true);
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer test-token");
        Pageable pageable = PageRequest.of(0, 20);
        Page<UnifiedLogEntryResponse> page = new PageImpl<>(List.of());
        when(unifiedOperationLogService.list(1L, true, "AI_JOB", "draft", pageable, "Bearer test-token"))
                .thenReturn(page);

        Page<UnifiedLogEntryResponse> result = controller.listUnified("AI_JOB", "draft", pageable);

        assertEquals(page, result);
    }

    @Test
    void listUnified_未ログインならForbidden() {
        OperationLogController controller = controller();
        when(currentActorService.getCurrentActorId()).thenReturn(null);
        Pageable pageable = PageRequest.of(0, 20);

        assertThrows(ForbiddenException.class, () -> controller.listUnified(null, null, pageable));
    }
}
