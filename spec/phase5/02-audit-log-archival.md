# 02. 監査ログのアーカイブ・削除ポリシー自動化

## 目的

Phase 4で実装した監査ログ機能において、1年以上の古いログを自動削除する定期スケジューラーを実装する。これにより、データベース容量の無制限増加を防ぎ、長期運用での性能維持・コンプライアンス要件(GDPR等の個人データ保持期限)に対応する。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 削除対象期間 | 1年(365日)以上前のログ |
| 削除スケジュール | 毎日 UTC 02:00(深夜)に実行、業務時間を避けた低負荷時を選定 |
| 実行方式 | Spring の `@Scheduled` アノテーション、cron式 `"0 0 2 * * *"` |
| アーカイブ | 削除前にCSV形式で出力(ローカルファイルシステム)、optional |
| ログレベル | 削除実行時にINFOレベルで件数を記録、エラー時はERROR |
| トランザクション | 大量削除時のロック影響を回避するため、バッチ削除(1000件単位等)を検討 |

## コンポーネント構成

### `AuditLogArchivalScheduler.java`

```java
package com.letsblog.api.scheduler;

import com.letsblog.api.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Component
@Slf4j
public class AuditLogArchivalScheduler {

    private final AuditLogService auditLogService;

    public AuditLogArchivalScheduler(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 毎日 UTC 02:00 に実行。1年以上前のログを削除する。
     * Cron式: "0 0 2 * * *" (秒 分 時 日 月 曜日)
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    public void archiveAndDeleteOldLogs() {
        try {
            log.info("Starting audit log archival and deletion job...");

            // 1年以前のタイムスタンプを計算
            LocalDateTime threshold = LocalDateTime.now().minus(365, ChronoUnit.DAYS);

            // 削除対象のログ件数を事前に取得
            long deletedCount = auditLogService.deleteLogsOlderThan(threshold);

            log.info("Audit log deletion completed. Deleted {} records older than {}", deletedCount, threshold);
        } catch (Exception e) {
            log.error("Failed to execute audit log archival job: {}", e.getMessage(), e);
        }
    }
}
```

### `AuditLogService` への新メソッド

```java
// AuditLogService.java の既存メソッドに以下を追加

/**
 * 指定された日時より前のすべての監査ログを削除する。
 * バッチ処理(1000件単位)で実行し、大量削除時の DB ロック影響を最小化。
 *
 * @param threshold 削除対象の日時(これより前のログを削除)
 * @return 削除されたログ件数
 */
@Transactional
public long deleteLogsOlderThan(LocalDateTime threshold) {
    List<AuditLog> oldLogs = auditLogRepository.findByCreatedAtBefore(threshold);

    if (oldLogs.isEmpty()) {
        log.info("No audit logs found older than {}", threshold);
        return 0;
    }

    long totalDeleted = 0;
    final int batchSize = 1000;

    for (int i = 0; i < oldLogs.size(); i += batchSize) {
        int end = Math.min(i + batchSize, oldLogs.size());
        List<AuditLog> batch = oldLogs.subList(i, end);

        auditLogRepository.deleteAll(batch);
        totalDeleted += batch.size();

        log.debug("Batch deleted {} logs", batch.size());
    }

    return totalDeleted;
}

/**
 * 削除対象のログをCSV形式でアーカイブする(optional)。
 */
@Transactional(readOnly = true)
public String archiveLogsAsCSV(LocalDateTime threshold) {
    List<AuditLog> oldLogs = auditLogRepository.findByCreatedAtBefore(threshold);

    StringBuilder csv = new StringBuilder();
    csv.append("ID,UserID,Action,ResourceType,ResourceID,Changes,RemoteIP,UserAgent,CreatedAt\n");

    for (AuditLog log : oldLogs) {
        csv.append(log.getId()).append(",")
                .append(log.getUserId() != null ? log.getUserId() : "").append(",")
                .append(log.getAction()).append(",")
                .append(log.getResourceType() != null ? log.getResourceType() : "").append(",")
                .append(log.getResourceId() != null ? log.getResourceId() : "").append(",")
                .append("\"").append(escapeCSV(log.getChanges() != null ? log.getChanges() : "")).append("\"").append(",")
                .append(log.getRemoteIp() != null ? log.getRemoteIp() : "").append(",")
                .append("\"").append(escapeCSV(log.getUserAgent() != null ? log.getUserAgent() : "")).append("\"").append(",")
                .append(log.getCreatedAt()).append("\n");
    }

    return csv.toString();
}

private String escapeCSV(String value) {
    return value.replace("\"", "\"\"");
}
```

### `SchedulerConfiguration.java`

```java
package com.letsblog.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class SchedulerConfiguration {
    // Spring の @Scheduled アノテーションを有効化
}
```

### Flyway マイグレーション (optional: アーカイブログテーブル)

```sql
-- V5__add_audit_log_archive_table.sql (optional)
CREATE TABLE audit_log_archives (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    archive_date DATETIME NOT NULL,
    threshold_date DATETIME NOT NULL,
    archive_file_path VARCHAR(500),
    record_count INT NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_archive_date (archive_date)
);
```

## タスクチェックリスト

- [ ] `AuditLogArchivalScheduler.java` 実装
- [ ] `SchedulerConfiguration.java` 実装(`@EnableScheduling`)
- [ ] `AuditLogService.deleteLogsOlderThan()` メソッド実装
- [ ] `AuditLogService.archiveLogsAsCSV()` メソッド実装(optional)
- [ ] Flyway マイグレーション `V5__add_audit_log_archive_table.sql` 作成(optional)
- [ ] `AuditLogArchivalSchedulerTest` 実装(Mockito で定期実行をシミュレート)
- [ ] `AuditLogServiceTest` に削除メソッドのテストケース追加
- [ ] スケジューラー起動確認(ログ出力で毎日 02:00 に実行されることを確認)
- [ ] 大量ログ削除のパフォーマンステスト(データベースロック時間の測定)
- [ ] `./gradlew test` でテスト PASS 確認

## 未決事項

- アーカイブファイルの保管先(ローカルファイルシステム vs クラウドストレージ)
- アーカイブファイルの圧縮(gzip等)の必要性
- アーカイブログの長期保管ポリシー(何年保持するか)
- 削除件数が多い場合の通知・アラート(Slack/メール等)
- 削除実行中の エラーハンドリング(部分的に失敗した場合の リトライ)
- 大量削除時のパフォーマンス影響の監視方法
- スケジュール実行時刻の カスタマイズ(環境変数化)
