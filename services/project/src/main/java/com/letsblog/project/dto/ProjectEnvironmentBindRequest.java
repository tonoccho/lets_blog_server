package com.letsblog.project.dto;

import jakarta.validation.constraints.NotNull;

public record ProjectEnvironmentBindRequest(
        @NotNull String environment,
        @NotNull Long siteId
) {
}
