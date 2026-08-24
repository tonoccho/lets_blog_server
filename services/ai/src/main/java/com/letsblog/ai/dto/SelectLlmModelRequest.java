package com.letsblog.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record SelectLlmModelRequest(@NotBlank String modelName) {
}
