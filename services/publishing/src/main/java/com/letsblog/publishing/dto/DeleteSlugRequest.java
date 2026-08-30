package com.letsblog.publishing.dto;

import jakarta.validation.constraints.NotBlank;

public record DeleteSlugRequest(
        @NotBlank String slug
) {
}
