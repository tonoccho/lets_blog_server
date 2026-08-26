package com.letsblog.project.dto;

import com.letsblog.project.cms.CmsType;
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
