package com.letsblog.logwriter.service;

import com.letsblog.logwriter.domain.AuditLog;
import com.letsblog.logwriter.testsupport.LogWriterIntegrationTestBase;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** AuditLogService(issue #1829で書き直し)。リポジトリは実物(実 MySQL)。区切り削除が複数回に分かれる場合は LogRetentionDeletionIntegrationTest が通す。 */
class AuditLogServiceTest extends LogWriterIntegrationTestBase {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final PageRequest PAGE = PageRequest.of(0, 20);

    @Autowired
    private AuditLogService service;

    private List<String> actions(org.springframework.data.domain.Page<AuditLog> page) {
        return page.map(AuditLog::getAction).getContent();
    }

    @Test
    void findByUserId_そのユーザーの行だけを新しい順に返す() {
        saveAudit(1L, "A_OLD", "USER", 1L, null, AT);
        saveAudit(1L, "A_NEW", "USER", 1L, null, AT.plusMinutes(1));
        saveAudit(2L, "B", "USER", 1L, null, AT);

        assertEquals(List.of("A_NEW", "A_OLD"), actions(service.findByUserId(1L, PAGE)));
    }

    @Test
    void findByAction_そのactionの行だけを返す() {
        saveAudit(1L, "USER_CREATED", "USER", 1L, null, AT);
        saveAudit(1L, "USER_DELETED", "USER", 1L, null, AT);

        assertEquals(List.of("USER_CREATED"), actions(service.findByAction("USER_CREATED", PAGE)));
    }

    @Test
    void findByDateRange_期間内の行だけを返す() {
        saveAudit(1L, "BEFORE", "USER", 1L, null, AT.minusDays(1));
        saveAudit(1L, "INSIDE", "USER", 1L, null, AT.plusHours(1));
        saveAudit(1L, "AFTER", "USER", 1L, null, AT.plusDays(2));

        assertEquals(List.of("INSIDE"), actions(service.findByDateRange(AT, AT.plusDays(1), PAGE)));
    }

    @Test
    void findAll_全件を新しい順に返す() {
        saveAudit(1L, "FIRST", "USER", 1L, null, AT);
        saveAudit(2L, "SECOND", "USER", 1L, null, AT.plusMinutes(1));

        assertEquals(List.of("SECOND", "FIRST"), actions(service.findAll(PAGE)));
    }

    @Test
    void deleteOldLogs_365日より古い行だけを削除する() {
        LocalDateTime now = LocalDateTime.now();
        saveAudit(1L, "OLD", "USER", 1L, null, now.minusDays(366));
        saveAudit(1L, "NEW", "USER", 1L, null, now.minusDays(364));

        service.deleteOldLogs();

        assertEquals(List.of("NEW"), auditLogRepository.findAll().stream().map(AuditLog::getAction).toList());
    }

    @Test
    void deleteOldLogs_対象が無ければ何も消さない() {
        saveAudit(1L, "NEW", "USER", 1L, null, LocalDateTime.now());

        service.deleteOldLogs();

        assertEquals(1, auditLogRepository.count());
    }

    @Test
    void deleteOldLogs_毎日UTC午前2時にスケジュール実行される() throws NoSuchMethodException {
        Method method = AuditLogService.class.getMethod("deleteOldLogs");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("0 0 2 * * *", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
    }
}
