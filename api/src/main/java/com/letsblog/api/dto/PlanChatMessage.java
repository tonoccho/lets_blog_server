package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record PlanChatMessage(
        @NotBlank String role,
        @NotBlank String content
) {
}
