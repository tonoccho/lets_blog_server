package com.letsblog.logwriter.controller;

import com.letsblog.logwriter.domain.AuditLog;
import com.letsblog.logwriter.service.AdminAuthorizationService;
import com.letsblog.logwriter.service.AuditLogService;
import com.letsblog.logwriter.service.ForbiddenException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogControllerTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private AuditLogController controller() {
        return new AuditLogController(auditLogService, adminAuthorizationService);
    }

    @Test
    void list_admin以外はForbidden() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().list(null, null, null, null, PageRequest.of(0, 20)));
    }

    @Test
    void list_userId指定でfindByUserIdへ委譲する() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<AuditLog> page = new PageImpl<>(List.of(new AuditLog()));
        when(auditLogService.findByUserId(1L, pageable)).thenReturn(page);

        Page<AuditLog> result = controller().list(1L, null, null, null, pageable);

        assertEquals(1, result.getTotalElements());
        verify(auditLogService).findByUserId(1L, pageable);
    }

    @Test
    void list_フィルタ無指定ならfindAllへ委譲する() {
        Pageable pageable = PageRequest.of(0, 20);
        when(auditLogService.findAll(pageable)).thenReturn(Page.empty());

        controller().list(null, null, null, null, pageable);

        verify(auditLogService).findAll(pageable);
    }
}
