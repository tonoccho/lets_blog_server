package com.letsblog.publishing.dto;

import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationLogLevel;
import com.letsblog.publishing.domain.BulkOperationSourceType;
import com.letsblog.publishing.domain.BulkOperationStatus;
import com.letsblog.publishing.domain.BulkOperationType;

import java.time.Instant;
import java.time.ZoneOffset;

public record BulkOperationLogResponse(
        BulkOperationType operationType,
        BulkOperationSourceType sourceType,
        String value,
        String categorySlug,
        String categoryParentSlug,
        String categoryTargetSlug,
        String categoryDescription,
        String originalFilename,
        String postStatus,
        String environment,
        BulkOperationStatus status,
        BulkOperationLogLevel level,
        String errorMessage,
        String stackTrace,
        Instant createdAt
) {
    public static BulkOperationLogResponse from(BulkOperationLog log) {
        return new BulkOperationLogResponse(
                log.getOperationType(), log.getSourceType(), log.getValue(),
                log.getCategorySlug(), log.getCategoryParentSlug(), log.getCategoryTargetSlug(),
                log.getCategoryDescription(),
                log.getOriginalFilename(), log.getPostStatus(), log.getEnvironment(), log.getStatus(), log.getLevel(),
                log.getErrorMessage(), log.getStackTrace(),
                // DB / エンティティの LocalDateTime は UTC の壁時計(#1257)。公開レスポンスは Z 終端 RFC 3339 で返す(#1540)。
                log.getCreatedAt() == null ? null : log.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
