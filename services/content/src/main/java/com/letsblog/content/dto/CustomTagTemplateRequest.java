package com.letsblog.content.dto;

import jakarta.validation.constraints.NotBlank;

public record CustomTagTemplateRequest(
        @NotBlank(message = "テンプレート名は必須です")
        String templateName,
        String description,
        String category,
        @NotBlank(message = "HTMLテンプレートは必須です")
        String htmlTemplate,
        String cssContent,
        Long projectId,
        Long originalTagId
) {
}
