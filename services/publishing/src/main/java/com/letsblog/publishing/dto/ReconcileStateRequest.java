package com.letsblog.publishing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReconcileStateRequest(
        @NotBlank String slug,
        @NotEmpty @Valid List<StateChangeRequest> changes
) {
    public record StateChangeRequest(
            @NotBlank String environment,
            @NotBlank String desiredStatus
    ) {
    }
}
