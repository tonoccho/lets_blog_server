package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SetProjectAdSenseClientSecretRequest(@NotBlank String clientSecret) {
}
