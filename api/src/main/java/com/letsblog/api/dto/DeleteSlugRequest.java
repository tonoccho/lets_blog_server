package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record DeleteSlugRequest(
        @NotBlank String slug
) {
}
