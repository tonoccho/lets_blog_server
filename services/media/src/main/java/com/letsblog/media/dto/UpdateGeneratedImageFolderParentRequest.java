package com.letsblog.media.dto;

/** フォルダの親の変更リクエスト(issue #1493)。{@code parentId}がnullなら最上位へ戻す。 */
public record UpdateGeneratedImageFolderParentRequest(Long parentId) {
}
