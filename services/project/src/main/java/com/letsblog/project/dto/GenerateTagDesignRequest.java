package com.letsblog.project.dto;

import jakarta.validation.constraints.NotBlank;

public record GenerateTagDesignRequest(@NotBlank String prompt) {
}
