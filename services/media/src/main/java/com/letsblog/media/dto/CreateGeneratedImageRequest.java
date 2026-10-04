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
        /** バッチ内の位置(0起点、issue #1101)。単発保存や#1101以前の行はnull。 */
        Integer batchIndex,
        String checkpoint,
        String loraName,
        Double loraWeight,
        @NotBlank String mimeType,
        @NotBlank String provider,
        String tagsJson,
        @NotNull byte[] imageData,
        /** img2imgの参照元の画像ID(issue #1601)。参照画像を使っていない画像はnull。 */
        Long sourceImageId) {

    /** 参照元を持たない保存(txt2img・アップロード・VSCode拡張等)向け。 */
    public CreateGeneratedImageRequest(
            Long projectId, String prompt, String negativePrompt, Integer steps, Double cfgScale,
            String samplerName, String scheduler, Long seed, Integer width, Integer height, Integer batchSize,
            Integer batchIndex, String checkpoint, String loraName, Double loraWeight, String mimeType,
            String provider, String tagsJson, byte[] imageData) {
        this(projectId, prompt, negativePrompt, steps, cfgScale, samplerName, scheduler, seed, width, height,
                batchSize, batchIndex, checkpoint, loraName, loraWeight, mimeType, provider, tagsJson, imageData,
                null);
    }
}
