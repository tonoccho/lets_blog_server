package com.letsblog.publishing.dto;

import jakarta.validation.constraints.NotBlank;

public record TermNameRequest(
        @NotBlank String slug
) {
}
