package com.letsblog.logwriter.dto;

import java.time.LocalDateTime;

/**
 * 操作ログ・AIジョブ・監査ログを一元表示するための共通形式(issue #187、#572でlog-writerへ移設)。
 * sourceTypeは"OPERATION"/"AI_JOB"/"AUDIT"のいずれか。
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
