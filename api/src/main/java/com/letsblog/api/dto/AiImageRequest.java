package com.letsblog.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * automatic1111相当のパラメータでのComfyUI画像生成リクエスト。
 * prompt以外は全て任意で、未指定分はComfyUiGenerationParams.withDefaults()相当の既定値/サーバー側解決値で補完される。
 */
public record AiImageRequest(
        @NotBlank String prompt,
        String negativePrompt,
        @Min(1) @Max(150) Integer steps,
        @DecimalMin("0.0") @DecimalMax("30.0") Double cfgScale,
        String samplerName,
        String scheduler,
        Long seed,
        @Min(64) @Max(2048) Integer width,
        @Min(64) @Max(2048) Integer height,
        @Min(1) @Max(4) Integer batchSize,
        String checkpoint,
        String loraName,
        @DecimalMin("0.0") @DecimalMax("2.0") Double loraWeight,
        Long projectId
) {
    public static AiImageRequest withDefaults(String prompt) {
        return new AiImageRequest(
                prompt, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @AssertTrue(message = "widthは8の倍数で指定してください")
    public boolean isWidthMultipleOf8() {
        return width == null || width % 8 == 0;
    }

    @AssertTrue(message = "heightは8の倍数で指定してください")
    public boolean isHeightMultipleOf8() {
        return height == null || height % 8 == 0;
    }
}
