package com.letsblog.media.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 生成画像ギャラリーの一括削除リクエスト(issue #1492)。 */
public record BulkDeleteGeneratedImagesRequest(@NotEmpty List<@NotNull Long> imageIds) {
}
