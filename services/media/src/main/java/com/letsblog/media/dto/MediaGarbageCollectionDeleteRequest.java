package com.letsblog.media.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** ガベージコレクション画面の削除リクエスト(issue #500)。 */
public record MediaGarbageCollectionDeleteRequest(@NotEmpty List<@NotBlank String> mediaIds) {
}
