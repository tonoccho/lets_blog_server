package com.letsblog.logwriter.controller;

import com.letsblog.logwriter.dto.OperationStat;
import com.letsblog.logwriter.dto.RouteStat;
import com.letsblog.logwriter.service.AdminAuthorizationService;
import com.letsblog.logwriter.service.ForbiddenException;
import com.letsblog.logwriter.service.OperationLogStatsService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** issue #1471: 集計APIはadmin限定。 */
@ExtendWith(MockitoExtension.class)
class OperationLogStatsControllerTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 2, 0, 0);

    @Mock
    private OperationLogStatsService service;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private OperationLogStatsController controller() {
        return new OperationLogStatsController(service, adminAuthorizationService);
    }

    @Test
    void routes_admin以外はForbiddenでサービスを呼ばない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().routes(START, END, null, null, null));
        verifyNoInteractions(service);
    }

    @Test
    void routes_adminならサービスへ委譲する() {
        List<RouteStat> expected = List.of(new RouteStat("GET", "/a", 1, 2L, 3L, 4L));
        when(service.routeStats(START, END, "p50", "asc", 5)).thenReturn(expected);

        assertEquals(expected, controller().routes(START, END, "p50", "asc", 5));
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void operations_admin以外はForbiddenでサービスを呼ばない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().operations(START, END, null, null, null));
        verifyNoInteractions(service);
    }

    @Test
    void operations_adminならサービスへ委譲する() {
        List<OperationStat> expected = List.of(new OperationStat("op-1", 10L, 2L, START, 1L));
        when(service.operationStats(START, END, null, null, null)).thenReturn(expected);

        assertEquals(expected, controller().operations(START, END, null, null, null));
        verify(adminAuthorizationService).requireAdmin();
    }
}
