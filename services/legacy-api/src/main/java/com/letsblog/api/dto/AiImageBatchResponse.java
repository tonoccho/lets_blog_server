package com.letsblog.api.dto;

import java.util.List;

/** batch_size分(最大4枚)の画像生成結果。 */
public record AiImageBatchResponse(List<AiImageResponse> images) {
}
