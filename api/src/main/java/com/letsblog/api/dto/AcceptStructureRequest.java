package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AcceptStructureRequest(
        @NotBlank(message = "構成案が空です") String structure
) {
}
