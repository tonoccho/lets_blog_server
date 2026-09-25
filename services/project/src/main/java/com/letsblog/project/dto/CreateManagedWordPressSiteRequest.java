package com.letsblog.project.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateManagedWordPressSiteRequest(
        @NotBlank String name,
        @NotBlank String siteKey,
        @NotBlank String title,
        @NotBlank String adminUser,
        @NotBlank @Email String adminEmail,
        @NotBlank String adminPassword,
        String locale,
        Long templateSiteId
) {
}
