package com.letsblog.project.dto;

import com.letsblog.project.cms.CmsType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** adminPath: 任意。未指定・空文字はNULL(グローバル既定を使う)として保存する(issue #1533)。 */
public record SiteRegisterRequest(
        @NotBlank String name,
        @NotBlank String siteKey,
        @NotNull CmsType cmsType,
        @NotEmpty Map<String, @NotBlank String> credentials,
        String adminPath
) {
}
