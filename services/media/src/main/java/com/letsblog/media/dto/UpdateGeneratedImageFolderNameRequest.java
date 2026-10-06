package com.letsblog.media.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** フォルダの改名リクエスト(issue #1494)。 */
public record UpdateGeneratedImageFolderNameRequest(@NotBlank @Size(max = 255) String name) {
}
