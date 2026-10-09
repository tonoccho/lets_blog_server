package com.letsblog.logwriter.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * log-writerの保存期間による削除が登録される(issue #1725)。{@code @EnableScheduling}が無いと{@code @Scheduled}は
 * 黙って登録されないため、アプリケーションコンテキストで実際にスケジュールされていることを確かめる。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("log-writer: @Scheduledの登録")
class ScheduledTasksRegistrationTest {

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Test
    @DisplayName("@Scheduledメソッドがスケジュールに登録されている")
    void スケジュールに登録されている() {
        // タスクのRunnableは実行時に包まれるが、toString()は元の「クラス名.メソッド名」を返す。
        Set<String> registered = scheduledTaskHolder.getScheduledTasks().stream()
                .map(task -> task.getTask().getRunnable().toString())
                .collect(Collectors.toSet());

        assertThat(registered).anyMatch(r -> r.endsWith("AuditLogService.deleteOldLogs"));
        assertThat(registered).anyMatch(r -> r.endsWith("OperationLogService.deleteOldLogs"));
    }
}
