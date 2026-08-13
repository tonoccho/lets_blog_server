package com.letsblog.api.dto;

import jakarta.validation.constraints.NotNull;

public record CustomTagPreviewRequest(
        @NotNull String htmlTemplate,
        String cssContent,
        @NotNull String testContent
) {
}
