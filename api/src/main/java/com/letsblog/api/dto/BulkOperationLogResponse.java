package com.letsblog.api.dto;

import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationSourceType;
import com.letsblog.api.domain.BulkOperationStatus;
import com.letsblog.api.domain.BulkOperationType;

import java.time.LocalDateTime;

public record BulkOperationLogResponse(
        Long id,
        BulkOperationType operationType,
        BulkOperationSourceType sourceType,
        String value,
        String categorySlug,
        String categoryParentSlug,
        String categoryTargetSlug,
        String categoryDescription,
        String originalFilename,
        String environment,
        BulkOperationStatus status,
        String errorMessage,
        String stackTrace,
        boolean isReplay,
        LocalDateTime createdAt
) {
    public static BulkOperationLogResponse from(BulkOperationLog log) {
        return new BulkOperationLogResponse(
                log.getId(), log.getOperationType(), log.getSourceType(), log.getValue(),
                log.getCategorySlug(), log.getCategoryParentSlug(), log.getCategoryTargetSlug(),
                log.getCategoryDescription(),
                log.getOriginalFilename(), log.getEnvironment(), log.getStatus(), log.getErrorMessage(),
                log.getStackTrace(), log.isReplay(), log.getCreatedAt());
    }
}
