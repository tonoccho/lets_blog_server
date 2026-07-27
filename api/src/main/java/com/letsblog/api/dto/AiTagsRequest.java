package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AiTagsRequest(@NotBlank String text) {
}
