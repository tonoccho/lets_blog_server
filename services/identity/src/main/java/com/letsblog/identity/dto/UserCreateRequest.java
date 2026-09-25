package com.letsblog.identity.dto;

import jakarta.validation.constraints.NotBlank;

public record UserCreateRequest(
        @NotBlank String email,
        @NotBlank String password,
        @NotBlank String role
) {
}
