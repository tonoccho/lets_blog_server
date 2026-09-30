package com.letsblog.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record SetProjectClaudeApiKeyRequest(@NotBlank String apiKey) {
}
