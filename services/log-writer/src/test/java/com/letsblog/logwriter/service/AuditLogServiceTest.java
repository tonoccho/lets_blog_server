package com.letsblog.logwriter.service;

import com.letsblog.logwriter.repository.AuditLogRepository;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    void deleteOldLogs_区切り件数ずつ端数の回で止まるまで削除する() {
        service = new AuditLogService(repository);
        int batch = AuditLogService.DEFAULT_DELETE_BATCH_SIZE;
        when(repository.deleteBatchBefore(any(LocalDateTime.class), eq(batch))).thenReturn(batch, batch, 3);

        service.deleteOldLogs();

        verify(repository, times(3)).deleteBatchBefore(any(LocalDateTime.class), eq(batch));
    }

    @Test
    void deleteOldLogs_対象が無ければ1回で止まる() {
        service = new AuditLogService(repository);
        when(repository.deleteBatchBefore(any(LocalDateTime.class), anyInt())).thenReturn(0);

        service.deleteOldLogs();

        verify(repository, times(1)).deleteBatchBefore(any(LocalDateTime.class), anyInt());
    }

    @Test
    void deleteOldLogs_全件をメモリに読み込まない() {
        service = new AuditLogService(repository);
        when(repository.deleteBatchBefore(any(LocalDateTime.class), anyInt())).thenReturn(0);

        service.deleteOldLogs();

        verify(repository, never()).findAll();
    }

    @Test
    void deleteOldLogs_毎日UTC午前2時にスケジュール実行される() throws NoSuchMethodException {
        Method method = AuditLogService.class.getMethod("deleteOldLogs");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("0 0 2 * * *", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
    }
}
