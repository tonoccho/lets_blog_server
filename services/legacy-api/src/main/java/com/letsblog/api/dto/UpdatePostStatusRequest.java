package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdatePostStatusRequest(
        @NotBlank String slug,
        @NotBlank String status
) {
}
