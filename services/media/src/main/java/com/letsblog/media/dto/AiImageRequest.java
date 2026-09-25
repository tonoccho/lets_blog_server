package com.letsblog.media.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * automatic1111相当のパラメータでのComfyUI画像生成リクエスト。
 * prompt以外は全て任意で、未指定分はComfyUiGenerationParams.withDefaults()相当の既定値/サーバー側解決値で補完される。
 *
 * <p>issue #1102: {@code batchSize}(1回の生成で作る枚数)の上限を4から16へ上げ、
 * {@code batchCount}(その生成を何回繰り返すか、既定1)を追加した。1リクエストで
 * {@code batchSize × batchCount}枚を生成する。<b>合計枚数の上限は設けない</b>
 * (最大256枚を受理する)。クライアントから複数回呼ばせないのは、{@code /api/ai/image}が
 * gatewayのupload-endpoint枠(プロセス全体で1時間に10回)に属し、リピートのたびに
 * 枠を消費すると同じ1時間の他の生成・アップロードを巻き添えにするため。
 *
 * <p>{@code batchSize}の実効上限はプロバイダによってさらに下がる
 * ({@link com.letsblog.media.ai.ImageProvider#maxBatchSize()})。ここの{@code @Max(16)}は
 * 全プロバイダ共通の上限で、プロバイダ個別の上限は実行時にプロバイダが決まってから
 * {@link com.letsblog.media.service.ImageGenerationService}が判定する。
 *
 * <p>issue #1102 レビュー指摘: {@code seed}の上限はComfyUIのKSamplerが扱う
 * 非負32bitの上限({@code 0xFFFFFFFF} = 4294967295)。上限が無いと、batch countの
 * リピートで{@code seed + repeatIndex}を計算するときに{@code Long}があふれ、
 * {@link com.letsblog.media.ai.SeedResolver}が保証する値域0..0xFFFFFFFFが壊れる。
 * <b>下限は設けない</b>。負のseedは「未指定と同じくランダム扱い」という#1101からの
 * ふるまいを保つため、400にせず{@code SeedResolver}がランダム値へ置き換える。
 */
public record AiImageRequest(
        @NotBlank String prompt,
        String negativePrompt,
        @Min(1) @Max(150) Integer steps,
        @DecimalMin("0.0") @DecimalMax("30.0") Double cfgScale,
        String samplerName,
        String scheduler,
        @Max(4294967295L) Long seed,
        @Min(64) @Max(2048) Integer width,
        @Min(64) @Max(2048) Integer height,
        @Min(1) @Max(16) Integer batchSize,
        @Min(1) @Max(16) Integer batchCount,
        String checkpoint,
        String loraName,
        @DecimalMin("0.0") @DecimalMax("2.0") Double loraWeight,
        Long projectId
) {
    public static AiImageRequest withDefaults(String prompt) {
        return new AiImageRequest(
                prompt, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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
