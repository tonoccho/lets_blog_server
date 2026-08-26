package com.letsblog.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record SyncEnvironmentRequest(
        @NotBlank String from,
        @NotBlank String to,
        @NotEmpty List<String> targets
) {
}
