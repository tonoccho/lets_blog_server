package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AiImageRequest(@NotBlank String prompt) {
}
