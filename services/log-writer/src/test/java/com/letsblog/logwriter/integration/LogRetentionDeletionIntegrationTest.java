package com.letsblog.logwriter.integration;

import com.letsblog.logwriter.repository.AuditLogRepository;
import com.letsblog.logwriter.repository.OperationLogRepository;
import com.letsblog.logwriter.service.AuditLogService;
import com.letsblog.logwriter.service.OperationLogService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * issue #1727: 保存期間による削除は、溜まった件数が多くても全件をメモリへ読み込まず区切って削除する(実MySQL)。
 * 区切り件数を5に下げ、5を超える古い行(12行)があっても全て消えることを確かめる。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "log.retention.delete-batch-size=5")
@DisplayName("ログの保存期間による削除: 区切り削除(issue #1727)")
class LogRetentionDeletionIntegrationTest {

    private static final String TAG = "ret1727-";
    private static final int OLD_ROWS = 12;
    private static final int NEW_ROWS = 3;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private OperationLogService operationLogService;

    @MockitoSpyBean
    private AuditLogRepository auditLogRepository;

    @MockitoSpyBean
    private OperationLogRepository operationLogRepository;

    @BeforeEach
    void seed() {
        cleanup();
        List<Object[]> audit = new ArrayList<>();
        List<Object[]> ops = new ArrayList<>();
        for (int i = 0; i < OLD_ROWS + NEW_ROWS; i++) {
            // 監査ログの保存期間は365日、操作ログは30日。どちらの閾値よりも十分古い/新しい行を作る。
            Timestamp at = Timestamp.valueOf(i < OLD_ROWS ? LocalDateTime.now().minusDays(1000) : LocalDateTime.now().minusMinutes(5));
            audit.add(new Object[] {"T1727", TAG + i, at});
            ops.add(new Object[] {TAG + i, "GET", "/x", 0L, true, at});
        }
        jdbc.batchUpdate("INSERT INTO audit_logs (action, user_agent, created_at) VALUES (?,?,?)", audit);
        jdbc.batchUpdate("INSERT INTO operation_logs (operation_id, method, path, duration_ms, success, created_at)"
                + " VALUES (?,?,?,?,?,?)", ops);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM audit_logs WHERE user_agent LIKE ?", TAG + "%");
        jdbc.update("DELETE FROM operation_logs WHERE operation_id LIKE ?", TAG + "%");
    }

    @Test
    @DisplayName("監査ログ: 区切り件数を超える古い行も全て消え、新しい行は残り、全件読み込みはしない")
    void auditLogsOlderThanThresholdAreAllDeleted() {
        auditLogService.deleteOldLogs();

        assertThat(count("audit_logs", "user_agent")).isEqualTo(NEW_ROWS);
        verify(auditLogRepository, never()).findAll();
    }

    @Test
    @DisplayName("操作ログ: 区切り件数を超える古い行も全て消え、新しい行は残り、全件読み込みはしない")
    void operationLogsOlderThanThresholdAreAllDeleted() {
        operationLogService.deleteOldLogs();

        assertThat(count("operation_logs", "operation_id")).isEqualTo(NEW_ROWS);
        verify(operationLogRepository, never()).findAll();
    }

    private int count(String table, String column) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " LIKE ?", Integer.class, TAG + "%");
    }
}
