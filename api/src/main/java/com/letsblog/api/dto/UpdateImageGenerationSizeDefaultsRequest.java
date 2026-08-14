package com.letsblog.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * プロジェクトごとの画像生成デフォルトサイズの更新リクエスト(issue #292)。
 * null(両方とも未指定)はアプリ全体のデフォルト(1920x1080)へのフォールバックを意味する。
 */
public record UpdateImageGenerationSizeDefaultsRequest(
        @Min(64) @Max(2048) Integer defaultGeneratedImageWidth,
        @Min(64) @Max(2048) Integer defaultGeneratedImageHeight
) {
    @AssertTrue(message = "widthは8の倍数で指定してください")
    public boolean isWidthMultipleOf8() {
        return defaultGeneratedImageWidth == null || defaultGeneratedImageWidth % 8 == 0;
    }

    @AssertTrue(message = "heightは8の倍数で指定してください")
    public boolean isHeightMultipleOf8() {
        return defaultGeneratedImageHeight == null || defaultGeneratedImageHeight % 8 == 0;
    }
}
