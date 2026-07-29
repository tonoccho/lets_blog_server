package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateProjectUserRequest(
        @NotBlank String wpRole
) {
}
