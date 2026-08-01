package com.letsblog.api.dto;

import com.letsblog.api.domain.BulkOperationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BulkOperationRequest(
        @NotNull BulkOperationType operationType,
        @NotBlank String value
) {
}
