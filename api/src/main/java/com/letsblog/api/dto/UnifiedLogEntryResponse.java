package com.letsblog.api.dto;

import java.time.LocalDateTime;

/**
 * 操作ログ・AIジョブ・監査ログを一元表示するための共通形式(issue #187)。
 * sourceTypeは"OPERATION"/"AI_JOB"/"AUDIT"のいずれか。
 */
public record UnifiedLogEntryResponse(
        String sourceType,
        Long id,
        LocalDateTime createdAt,
        String title,
        String detail,
        String status,
        String operationId
) {
}
