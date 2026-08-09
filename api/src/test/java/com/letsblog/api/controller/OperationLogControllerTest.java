package com.letsblog.api.controller;

import com.letsblog.api.domain.OperationLog;
import com.letsblog.api.dto.OperationLogRequest;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.ForbiddenException;
import com.letsblog.api.service.OperationLogService;
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

import java.util.List;

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
    private CurrentActorService currentActorService;

    private OperationLogController controller() {
        return new OperationLogController(service, currentActorService);
    }

    @Test
    void record_ログイン中ユーザーIDで保存する() {
        OperationLogController controller = controller();
        when(currentActorService.getCurrentActorId()).thenReturn(1L);
        OperationLogRequest request = new OperationLogRequest("op-1", "GET", "/api/sites", 200, 42L, true, null);

        ResponseEntity<Void> response = controller.record(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(service, times(1)).record(captor.capture());
        assertEquals(1L, captor.getValue().getUserId());
        assertEquals("op-1", captor.getValue().getOperationId());
    }

    @Test
    void record_未ログインならUnauthorizedを返す() {
        OperationLogController controller = controller();
        when(currentActorService.getCurrentActorId()).thenReturn(null);
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
}
