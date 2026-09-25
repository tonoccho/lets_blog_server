package com.letsblog.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record SetProjectBraveSearchApiKeyRequest(@NotBlank String apiKey) {
}
