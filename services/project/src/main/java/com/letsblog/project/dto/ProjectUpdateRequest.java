package com.letsblog.project.dto;

import jakarta.validation.constraints.NotBlank;

public record ProjectUpdateRequest(
        @NotBlank String name
) {
}
