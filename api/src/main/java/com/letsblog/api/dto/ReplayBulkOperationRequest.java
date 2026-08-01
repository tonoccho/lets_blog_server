package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ReplayBulkOperationRequest(
        @NotBlank String environment
) {
}
