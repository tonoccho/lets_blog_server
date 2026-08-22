package com.letsblog.api.dto;

import com.letsblog.api.domain.StaticContentType;
import jakarta.validation.constraints.NotNull;

public record GenerateStaticContentRequest(
    @NotNull(message = "コンテンツ種別は必須です") StaticContentType contentType
) {
}
