package com.letsblog.logwriter.dto;

import java.time.LocalDateTime;

/**
 * 操作ログ・AIジョブ・監査ログを一元表示するための共通形式(issue #187、#572でlog-writerへ移設)。
 * sourceTypeは"OPERATION"/"AI_JOB"/"SYSTEM_JOB"/"AUDIT"のいずれか。
 * AI_JOBとSYSTEM_JOBはgeneration_jobs由来で、ジョブ種別による分類結果(issue #1481)。
 */
public record UnifiedLogEntryResponse(
        String sourceType,
        Long id,
        LocalDateTime createdAt,
        String title,
        String detail,
        String status,
        String operationId,
        String actorKeycloakSub
) {
}
