package com.letsblog.content.dto;

import jakarta.validation.constraints.NotNull;

public record RenderPreviewRequest(
        @NotNull String markdown
) {
}
