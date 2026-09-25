package com.letsblog.logwriter.controller;

import com.letsblog.logwriter.domain.FrontendErrorLog;
import com.letsblog.logwriter.dto.FrontendErrorLogRequest;
import com.letsblog.logwriter.service.AdminAuthorizationService;
import com.letsblog.logwriter.service.ForbiddenException;
import com.letsblog.logwriter.service.FrontendErrorLogService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FrontendErrorLogControllerTest {

    @Mock
    private FrontendErrorLogService service;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private FrontendErrorLogController controller() {
        return new FrontendErrorLogController(service, adminAuthorizationService);
    }

    @Test
    void logError_登録できる() {
        FrontendErrorLogRequest request = new FrontendErrorLogRequest(
                "boom", null, null, "error", null, "https://example.com", "agent", null);

        ResponseEntity<Void> response = controller().logError(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<FrontendErrorLog> captor = ArgumentCaptor.forClass(FrontendErrorLog.class);
        verify(service, times(1)).logError(captor.capture());
        assertEquals("boom", captor.getValue().getMessage());
    }

    @Test
    void getErrors_admin以外はForbidden() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller().getErrors(null, null, null, null, PageRequest.of(0, 20)));
    }

    @Test
    void getErrors_level指定でfindByLevelへ委譲する() {
        Pageable pageable = PageRequest.of(0, 20);
        when(service.findByLevel("ERROR", pageable)).thenReturn(Page.empty());

        controller().getErrors("error", null, null, null, pageable);

        verify(service).findByLevel("ERROR", pageable);
    }

    @Test
    void getErrors_startDateとendDateが両方あればfindByDateRangeへ委譲する() {
        Pageable pageable = PageRequest.of(0, 20);
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 1, 2, 0, 0);
        when(service.findByDateRange(start, end, pageable)).thenReturn(Page.empty());

        controller().getErrors(null, start, end, null, pageable);

        verify(service).findByDateRange(start, end, pageable);
    }

    @Test
    void getErrors_startDateのみではfindByDateRangeへ委譲しない() {
        Pageable pageable = PageRequest.of(0, 20);
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        when(service.findAll(pageable)).thenReturn(Page.empty());

        controller().getErrors(null, start, null, null, pageable);

        verify(service, times(0)).findByDateRange(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(service).findAll(pageable);
    }

    @Test
    void getErrors_url指定でfindByUrlへ委譲する() {
        Pageable pageable = PageRequest.of(0, 20);
        when(service.findByUrl("https://example.com", pageable)).thenReturn(Page.empty());

        controller().getErrors(null, null, null, "https://example.com", pageable);

        verify(service).findByUrl("https://example.com", pageable);
    }

    @Test
    void getErrors_条件無しならfindAllへ委譲する() {
        Pageable pageable = PageRequest.of(0, 20);
        when(service.findAll(pageable)).thenReturn(Page.empty());

        controller().getErrors(null, null, null, null, pageable);

        verify(service).findAll(pageable);
    }
}
