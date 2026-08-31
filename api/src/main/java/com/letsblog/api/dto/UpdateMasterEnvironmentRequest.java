package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateMasterEnvironmentRequest(
        @NotBlank String masterEnvironment
) {
}
