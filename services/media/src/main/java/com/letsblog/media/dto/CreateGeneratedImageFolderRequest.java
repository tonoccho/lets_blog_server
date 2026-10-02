package com.letsblog.media.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** フォルダの作成リクエスト(issue #1493)。{@code parentId}がnullなら最上位。 */
public record CreateGeneratedImageFolderRequest(@NotBlank @Size(max = 255) String name, Long parentId) {
}
