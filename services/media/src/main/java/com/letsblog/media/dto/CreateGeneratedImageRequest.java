package com.letsblog.media.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * legacy-apiの{@code AiAssistService#generateImage}が、実際の画像生成(ComfyUI/ChatGPT呼び出し、
 * 引き続きlegacy-api側で行う)の後に、生成済み画像バイト列とパラメータを保存するために呼ぶ
 * (issue #573 stage4)。実際の生成AI呼び出し自体はlegacy-api側に残る(ComfyUiClient/
 * ChatGptImageClient/ImageModelServiceはいずれもこのissueの移設対象外。PR説明参照)ため、
 * ここではファイル保存(GeneratedImageStorageService)とDB行作成(generated_imagesテーブル、
 * media-serviceが所有)のみを担う。
 */
public record CreateGeneratedImageRequest(
        Long projectId,
        @NotBlank String prompt,
        String negativePrompt,
        Integer steps,
        Double cfgScale,
        String samplerName,
        String scheduler,
        Long seed,
        Integer width,
        Integer height,
        Integer batchSize,
        String checkpoint,
        String loraName,
        Double loraWeight,
        @NotBlank String mimeType,
        @NotBlank String provider,
        String tagsJson,
        @NotNull byte[] imageData) {
}
