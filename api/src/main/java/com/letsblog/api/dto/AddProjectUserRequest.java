package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddProjectUserRequest(
        @NotNull Long userId,
        @NotBlank String wpRole
) {
}
