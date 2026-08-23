package com.letsblog.logwriter.service;

import com.letsblog.logwriter.domain.AuditLog;
import com.letsblog.logwriter.repository.AuditLogRepository;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository repository;

    private AuditLogService service;

    @Test
    void findByUserId_リポジトリへ委譲する() {
        service = new AuditLogService(repository);
        PageRequest pageable = PageRequest.of(0, 20);

        service.findByUserId(1L, pageable);

        verify(repository).findByUserIdOrderByCreatedAtDesc(1L, pageable);
    }

    @Test
    void findByAction_リポジトリへ委譲する() {
        service = new AuditLogService(repository);
        PageRequest pageable = PageRequest.of(0, 20);

        service.findByAction("USER_CREATED", pageable);

        verify(repository).findByActionOrderByCreatedAtDesc("USER_CREATED", pageable);
    }

    @Test
    void deleteOldLogs_1年以上前のログのみ削除する() {
        service = new AuditLogService(repository);
        AuditLog oldLog = new AuditLog();
        when(repository.findByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(List.of(oldLog));

        service.deleteOldLogs();

        verify(repository, times(1)).deleteAll(List.of(oldLog));
    }

    @Test
    void deleteOldLogs_対象が無ければ削除処理を呼ばない() {
        service = new AuditLogService(repository);
        when(repository.findByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(List.of());

        service.deleteOldLogs();

        verify(repository, times(0)).deleteAll(any());
    }

    @Test
    void deleteOldLogs_毎日UTC午前2時にスケジュール実行される() throws NoSuchMethodException {
        Method method = AuditLogService.class.getMethod("deleteOldLogs");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("0 0 2 * * *", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
    }
}
