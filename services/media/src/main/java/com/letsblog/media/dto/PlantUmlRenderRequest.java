package com.letsblog.media.dto;

import jakarta.validation.constraints.NotBlank;

public record PlantUmlRenderRequest(@NotBlank String source) {
}
