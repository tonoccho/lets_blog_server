package com.letsblog.media.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * legacy-apiの{@code ComfyUiModelService#startDelete}が起動する
 * {@code POST /api/comfyui/checkpoints/delete}のリクエスト(#573 stage2)。
 */
public record DeleteComfyUiCheckpointCommand(@NotNull Long jobId, @NotBlank String fileName) {
}
