package com.letsblog.logwriter.dto;

import java.time.LocalDateTime;

/** 操作別集計の1行(issue #1471)。operation_idごとの合計所要時間・API呼び出し数・開始時刻・利用者ID。 */
public record OperationStat(
        String operationId, long totalDurationMs, long callCount, LocalDateTime startedAt, Long userId) {
}
