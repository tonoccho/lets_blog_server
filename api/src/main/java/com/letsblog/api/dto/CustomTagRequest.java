package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CustomTagRequest(
        @NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_-]*$", message = "英数字・ハイフン・アンダースコアのみ使用できます")
        String tagName,
        @NotBlank String htmlTemplate,
        String description,
        String cssContent
) {
}
