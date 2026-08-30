package com.letsblog.media.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * legacy-apiの{@code ComfyUiModelService#startInstall}が起動する
 * {@code POST /api/comfyui/checkpoints/install}のリクエスト(#573 stage2)。jobIdは
 * legacy-api側で既に作成済みのGenerationJobのIDで、進捗・完了はこのjobId宛に
 * {@code PATCH /api/generation-jobs/{id}}で反映される。
 */
public record InstallComfyUiCheckpointCommand(
        @NotNull Long jobId,
        @NotBlank String downloadUrl,
        @NotBlank String fileName) {
}
