package com.letsblog.project.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateMasterEnvironmentRequest(
        @NotBlank String masterEnvironment
) {
}
