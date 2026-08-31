package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AdoptWordPressSiteRequest(
        @NotBlank String name,
        @NotBlank String siteKey,
        @NotBlank String adminUser
) {
}
