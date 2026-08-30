package com.letsblog.content.dto;

import jakarta.validation.constraints.NotBlank;

public record ValidateCustomTagRequest(
    @NotBlank(message = "HTMLテンプレートは必須です") String htmlTemplate,
    String cssContent
) {
}
