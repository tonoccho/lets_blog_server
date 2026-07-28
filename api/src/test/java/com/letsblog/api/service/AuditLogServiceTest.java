package com.letsblog.api.service;

import com.letsblog.api.domain.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditLogService service;

    @Test
    void log_監査ログを保存する() {
        service = new AuditLogService(auditLogRepository);

        service.log(1L, AuditLogAction.USER_CREATED, "USER", 2L, "{}", "127.0.0.1", "test-agent");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertEquals(1L, saved.getUserId());
        assertEquals(AuditLogAction.USER_CREATED, saved.getAction());
        assertEquals("USER", saved.getResourceType());
        assertEquals(2L, saved.getResourceId());
        assertEquals("127.0.0.1", saved.getRemoteIp());
    }

    @Test
    void deleteOldLogs_1年以上前のログのみ削除する() {
        service = new AuditLogService(auditLogRepository);
        AuditLog oldLog = new AuditLog();
        when(auditLogRepository.findByCreatedAtBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(oldLog));

        service.deleteOldLogs();

        verify(auditLogRepository, times(1)).deleteAll(List.of(oldLog));
    }

    @Test
    void deleteOldLogs_対象が無ければ削除処理を呼ばない() {
        service = new AuditLogService(auditLogRepository);
        when(auditLogRepository.findByCreatedAtBefore(any(LocalDateTime.class)))
                .thenReturn(List.of());

        service.deleteOldLogs();

        verify(auditLogRepository, times(0)).deleteAll(any());
    }
}
