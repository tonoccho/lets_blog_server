package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyTotpRequest(
        @NotBlank(message = "TOTPコードは必須です") String code
) {
}
