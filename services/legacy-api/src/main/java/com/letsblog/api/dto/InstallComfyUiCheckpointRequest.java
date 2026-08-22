package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record InstallComfyUiCheckpointRequest(
        @NotBlank String downloadUrl,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]+$", message = "ファイル名は英数字・アンダースコア・ハイフン・ピリオドのみ使用できます")
        String fileName) {
}
