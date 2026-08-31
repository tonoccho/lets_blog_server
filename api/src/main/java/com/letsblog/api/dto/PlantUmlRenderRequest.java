package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record PlantUmlRenderRequest(@NotBlank String source) {
}
