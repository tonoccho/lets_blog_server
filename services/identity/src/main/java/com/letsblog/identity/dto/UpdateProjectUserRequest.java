package com.letsblog.identity.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateProjectUserRequest(
        @NotBlank String wpRole
) {
}
