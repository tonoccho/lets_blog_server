package com.letsblog.media.dto;

/**
 * 生成した画像1枚分の結果。
 *
 * <p>{@code seed}と{@code batchIndex}はissue #1101で追加した。生成に実際に使われたseedが
 * 返らないと、ギャラリーの「この設定で画像生成」(#294)・「この画像の設定をコピー」(#437)から
 * 同じ画像を作り直せない。{@code batchIndex}は{@code batchSize > 1}のときの
 * バッチ内の位置(0起点)で、同一バッチの複数枚を区別するために持つ。
 *
 * <p>{@code seed}はCOMFYUIプロバイダでのみ非nullになる。ChatGPTの画像生成API(gpt-image-1)は
 * seedを受け付けず再現できないため、CHATGPT由来の画像ではnullのままにする。
 */
public record AiImageResponse(
        Long id,
        String fileName,
        String dataBase64,
        String mimeType,
        Long seed,
        Integer batchIndex) {
}
