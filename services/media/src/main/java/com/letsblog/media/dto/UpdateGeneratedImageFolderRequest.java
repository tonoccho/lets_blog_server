package com.letsblog.media.dto;

/** 画像の所属フォルダの変更リクエスト(issue #1493)。{@code folderId}がnullなら未分類へ戻す。 */
public record UpdateGeneratedImageFolderRequest(Long folderId) {
}
