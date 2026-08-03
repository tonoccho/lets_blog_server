package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record InstallOllamaModelRequest(@NotBlank String modelName) {
}
