package com.letsblog.platform.dto;

import jakarta.validation.constraints.NotBlank;

public record SetBraveSearchApiKeyRequest(@NotBlank String apiKey) {
}
