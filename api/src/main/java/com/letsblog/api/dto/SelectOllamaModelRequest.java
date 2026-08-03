package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SelectOllamaModelRequest(@NotBlank String modelName) {
}
