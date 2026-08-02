package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateUserPreferencesRequest(
        @NotBlank String locale,
        @NotBlank String timezone
) {
}
