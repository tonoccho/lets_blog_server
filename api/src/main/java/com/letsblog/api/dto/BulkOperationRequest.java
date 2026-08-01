package com.letsblog.api.dto;

import com.letsblog.api.domain.BulkOperationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BulkOperationRequest(
        @NotNull BulkOperationType operationType,
        @NotBlank String value,
        // 以下3項目はoperationType=CATEGORYの場合のみ有効(PLUGIN/THEMEでは無視される)
        String categorySlug,
        String categoryParentName,
        String categoryDescription
) {
}
