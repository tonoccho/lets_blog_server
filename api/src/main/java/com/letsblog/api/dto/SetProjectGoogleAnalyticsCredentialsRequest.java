package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SetProjectGoogleAnalyticsCredentialsRequest(
        @NotBlank String propertyId,
        @NotBlank String serviceAccountJson
) {
}
