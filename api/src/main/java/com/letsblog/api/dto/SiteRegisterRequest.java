package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SiteRegisterRequest(
        @NotBlank String name,
        @NotBlank String siteKey,
        @NotBlank String baseUrl,
        @NotBlank String wpUsername,
        @NotBlank String wpAppPassword
) {
}
