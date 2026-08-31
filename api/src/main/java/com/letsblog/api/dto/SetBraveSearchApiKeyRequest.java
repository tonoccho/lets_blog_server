package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SetBraveSearchApiKeyRequest(@NotBlank String apiKey) {
}
