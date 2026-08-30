package com.letsblog.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ProjectCreateRequest(
        @NotBlank String name,
        @NotBlank @Pattern(regexp = "^[a-z0-9-]+$", message = "slugは半角英数字とハイフンのみ使用できます") String slug
) {
}
