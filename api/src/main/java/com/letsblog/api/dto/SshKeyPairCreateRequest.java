package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SshKeyPairCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 255) String comment) {
}
