package com.letsblog.api.dto;

import jakarta.validation.constraints.NotNull;

public record RenderPreviewRequest(
        @NotNull String markdown
) {
}
