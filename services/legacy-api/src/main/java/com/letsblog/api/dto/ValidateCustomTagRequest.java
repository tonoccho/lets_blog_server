package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ValidateCustomTagRequest(
    @NotBlank(message = "HTMLテンプレートは必須です") String htmlTemplate,
    String cssContent
) {
}
