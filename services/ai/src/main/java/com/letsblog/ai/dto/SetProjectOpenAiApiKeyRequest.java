package com.letsblog.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record SetProjectOpenAiApiKeyRequest(@NotBlank String apiKey) {
}
