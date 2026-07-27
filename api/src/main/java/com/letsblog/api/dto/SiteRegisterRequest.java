package com.letsblog.api.dto;

import com.letsblog.api.cms.CmsType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record SiteRegisterRequest(
        @NotBlank String name,
        @NotBlank String siteKey,
        @NotNull CmsType cmsType,
        @NotEmpty Map<String, @NotBlank String> credentials
) {
}
