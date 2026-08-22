package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CloneCustomTagTemplateRequest(
        @NotBlank(message = "新しいテンプレート名は必須です")
        String newTemplateName,
        String description,
        String category,
        Long projectId
) {
}
