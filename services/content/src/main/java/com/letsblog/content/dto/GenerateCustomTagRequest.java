package com.letsblog.content.dto;

import jakarta.validation.constraints.NotBlank;

public record GenerateCustomTagRequest(
    @NotBlank(message = "プロンプトは必須です") String prompt,
    @NotBlank(message = "タグ名は必須です") String tagName,
    String description,
    Long projectId
) {
}
