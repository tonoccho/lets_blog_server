package com.letsblog.project.dto;

import com.letsblog.project.domain.StaticContentType;
import jakarta.validation.constraints.NotNull;

public record GenerateStaticContentRequest(
        @NotNull(message = "コンテンツ種別は必須です") StaticContentType contentType
) {
}
