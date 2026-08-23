package com.letsblog.identity.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateUserPreferencesRequest(
        @NotBlank String locale,
        @NotBlank String timezone
) {
}
